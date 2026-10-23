/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Presents a Tidal/LastWave fragmented DASH stream as one progressive byte stream.
 *
 * FLAC renditions are remuxed into a native FLAC stream (fLaC magic + metadata from the init
 * segment + the raw mdat frame payloads). Non-FLAC renditions (Dolby Atmos E-AC-3 JOC, AAC)
 * pass through as plain fragmented MP4 — init + media segments concatenated — so ExoPlayer's
 * FragmentedMp4Extractor reads the real track format and picks the correct decoder.
 *
 * Seeks are answered from a lazily built segment index: every media segment is probed once
 * with a small Range request (the CDN honours Range), yielding its exact box layout and size.
 * A seek then fetches ONLY the segment containing the target byte instead of sequentially
 * downloading everything before it — the difference between resuming in a couple of seconds
 * and stalling behind a full re-download of the track.
 */
internal class TidalProgressiveDashDataSource(
    private val httpClient: OkHttpClient,
) : BaseDataSource(true) {
    private var currentUri: Uri? = null
    private var segmentUrls: List<String> = emptyList()
    private var nextSegmentIndex = 0
    private var pendingBytes = ByteArray(0)
    private var pendingOffset = 0
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var flacRemux = true

    /** The header served before the first media segment (fLaC+metadata, or the init segment). */
    private var headerBytes = ByteArray(0)

    /** Absolute stream offset of pendingBytes[0]; -1 while unknown. */
    private var streamOffsetOfPending = -1L

    /** Exact per-segment layout; built lazily on the first seek. */
    private var segmentIndex: List<SegmentInfo>? = null

    private class SegmentInfo(
        val url: String,
        /** Offset of the mdat payload inside the fMP4 segment (after moof + mdat headers). */
        val payloadOffset: Long,
        /** Bytes this segment contributes to the synthesized stream. */
        val streamSize: Long,
        /** Total size of the fMP4 segment on the CDN. */
        val totalSize: Long,
    )

    override fun open(dataSpec: DataSpec): Long {
        val manifestPath =
            dataSpec.uri.getQueryParameter("manifest")
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("TIDAL progressive stream did not include a manifest")
        val manifestFile = File(manifestPath)
        if (!manifestFile.isFile) throw IOException("TIDAL progressive manifest is unavailable")

        val manifestText = manifestFile.readText(Charsets.UTF_8)
        val urls =
            runCatching {
                TidalAudioProvider.progressiveDashSegmentUrls(manifestText)
            }.getOrElse { error ->
                throw IOException("TIDAL progressive manifest could not be parsed", error)
            }
        if (urls.size < 2 || urls.any { !it.startsWith("http://") && !it.startsWith("https://") }) {
            throw IOException("TIDAL progressive manifest did not contain absolute FLAC segments")
        }

        // The segment URLs always come from the manifest's FIRST representation, so the
        // remux decision must follow that same representation's codec.
        val firstCodec = MANIFEST_CODEC.find(manifestText)?.groupValues?.getOrNull(1)?.trim()?.lowercase().orEmpty()
        flacRemux = firstCodec.isBlank() || firstCodec.startsWith("flac")

        currentUri = dataSpec.uri
        segmentUrls = urls
        nextSegmentIndex = 1

        // Build the exact segment index up front (both paths). The probes are small parallel
        // Range reads and complete in a few hundred milliseconds; without the index the FLAC
        // path cannot publish an exact SEEKTABLE and media3 falls back to a constant-bitrate
        // seek map that re-estimates as VBR frames parse — the restored-position seek then
        // re-targets forever while the clock advances, and the renderer stays silent chasing
        // it ("timer runs, no sound"). Best-effort: without the index the seek path degrades
        // to the sequential skip, exactly as before.
        runCatching { ensureSegmentIndex() }

        headerBytes =
            if (flacRemux) {
                val metadata = fetchSegment(urls.first(), 0)
                val withTable = appendFlacSeekTable(metadata, manifestText)
                byteArrayOf(
                    'f'.code.toByte(),
                    'L'.code.toByte(),
                    'a'.code.toByte(),
                    'C'.code.toByte(),
                ) + withTable
            } else {
                fetchSegment(urls.first(), 0)
            }
        pendingBytes = headerBytes
        pendingOffset = 0
        streamOffsetOfPending = 0L

        val estimatedTotalBytes = headerBytes.size.toLong() + (urls.size - 1).toLong() * 200_000L
        opened = true
        transferStarted(dataSpec)
        Timber.tag("TidalDash").d("open pos=%d remux=%b urls=%d headerSize=%d index=%b", dataSpec.position, flacRemux, urls.size, headerBytes.size, segmentIndex != null)

        try {
            if (dataSpec.position > 0L) jumpToAbsolutePosition(dataSpec.position)
            // With the segment index built, the exact total replaces the per-segment estimate —
            // an underestimate would truncate playback right after the seek.
            val exactTotal = segmentIndex?.let { index -> streamOffsetOfSegment(index, index.size) }
            bytesRemaining =
                when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    exactTotal != null -> (exactTotal - dataSpec.position).coerceAtLeast(1L)
                    else -> (estimatedTotalBytes - dataSpec.position).coerceAtLeast(1L)
                }
            return bytesRemaining
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: Throwable) {
            close()
            if (error is IOException) throw error
            throw IOException("TIDAL progressive stream seek failed", error)
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("TIDAL progressive stream is not open")
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (pendingOffset >= pendingBytes.size && !loadNextSegment()) {
            return C.RESULT_END_OF_INPUT
        }

        var available = pendingBytes.size - pendingOffset
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            available = minOf(available.toLong(), bytesRemaining).toInt()
        }
        if (available <= 0) return C.RESULT_END_OF_INPUT

        val count = minOf(length, available)
        pendingBytes.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    private fun loadNextSegment(): Boolean {
        if (nextSegmentIndex >= segmentUrls.size) return false
        val index = nextSegmentIndex
        if (streamOffsetOfPending >= 0L) streamOffsetOfPending += pendingBytes.size
        pendingBytes = fetchSegment(segmentUrls[index], index)
        Timber.tag("TidalDash").d("nextSegment urlIndex=%d size=%d streamOffset=%d first8=%s", index, pendingBytes.size, streamOffsetOfPending, pendingBytes.take(8).joinToString("") { "%02x".format(it) })
        pendingOffset = 0
        nextSegmentIndex++
        return pendingBytes.isNotEmpty()
    }

    private fun fetchSegment(
        url: String,
        segmentIndex: Int,
    ): ByteArray {
        val bytes = fetchRange(url, from = 0, toInclusive = null, label = "segment ${segmentIndex + 1}")
        return when {
            !flacRemux -> bytes
            segmentIndex == 0 -> TidalAudioProvider.progressiveDashFlacMetadata(bytes)
            else -> TidalAudioProvider.progressiveDashAudioPayload(bytes)
        }
    }

    /**
     * Appends a real FLAC SEEKTABLE to the remuxed metadata so ExoPlayer seeks by exact
     * sample/byte points instead of a constant-bitrate estimate. Each media segment begins on
     * a frame boundary (fMP4 samples are whole FLAC frames), so one seek point per segment —
     * sample number from the manifest's SegmentTimeline, byte offset from the probed index —
     * is both exact and cheap. Offsets are relative to the first frame, so growing the header
     * does not invalidate them. Returns the metadata unchanged when anything is missing.
     */
    private fun appendFlacSeekTable(
        metadata: ByteArray,
        manifestText: String,
    ): ByteArray {
        if (metadata.size < 38) return metadata
        val index = segmentIndex ?: return metadata
        val durations = parseSegmentTimelineDurations(manifestText) ?: return metadata
        val timescale = MANIFEST_TIMESCALE.find(manifestText)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return metadata
        val sampleRate = MANIFEST_SAMPLERATE.find(manifestText)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return metadata
        if (timescale <= 0L || sampleRate <= 0L) return metadata
        if (durations.size != index.size) return metadata

        val points = index.size * 18
        val out = ByteArray(metadata.size + 4 + points)
        metadata.copyInto(out, 0)
        // The existing final block (STREAMINFO) is no longer last: clear its last-block flag.
        out[0] = (out[0].toInt() and 0x7F).toByte()
        var cursor = metadata.size
        // SEEKTABLE = block type 3, and it is now the last block.
        out[cursor] = (0x80 or 3).toByte()
        out[cursor + 1] = ((points shr 16) and 0xFF).toByte()
        out[cursor + 2] = ((points shr 8) and 0xFF).toByte()
        out[cursor + 3] = (points and 0xFF).toByte()
        cursor += 4
        var sampleNumber = 0L
        var bytesBeforeSegment = 0L
        for (mediaIndex in index.indices) {
            val p = cursor + mediaIndex * 18
            writeUint64(out, p, sampleNumber)
            writeUint64(out, p + 8, bytesBeforeSegment)
            out[p + 16] = 0
            out[p + 17] = 1
            sampleNumber += durations[mediaIndex] * sampleRate / timescale
            bytesBeforeSegment += index[mediaIndex].streamSize
        }
        return out
    }

    /** Media-segment durations from the SegmentTimeline, timescale units, `r` expanded. */
    private fun parseSegmentTimelineDurations(manifestText: String): List<Long>? {
        val durations = mutableListOf<Long>()
        for (match in SEGMENT_TIMELINE_ENTRY.findAll(manifestText)) {
            val d = match.groupValues[1].toLongOrNull() ?: return null
            if (d <= 0L) return null
            val repeat = match.groupValues.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            repeat(repeat + 1) { durations += d }
        }
        return durations.takeIf { it.isNotEmpty() }
    }

    private fun writeUint64(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
        }
    }

    /** GETs the URL, optionally via `bytes=from-` / `bytes=from-to` Range. */
    private fun fetchRange(
        url: String,
        from: Long,
        toInclusive: Long?,
        label: String,
    ): ByteArray {
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", "audio/flac,audio/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "ArchiveTune-Android")
        if (toInclusive != null || from > 0L) {
            builder.header("Range", if (toInclusive != null) "bytes=$from-$toInclusive" else "bytes=$from-")
        }
        try {
            return httpClient.newCall(builder.get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("TIDAL $label HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("TIDAL $label was empty")
                Timber.tag("TidalDash").d(
                    "fetch %s code=%d bytes=%d first8=%s range=%s",
                    label, response.code, bytes.size,
                    bytes.take(8).joinToString("") { "%02x".format(it) },
                    response.header("Content-Range") ?: "-",
                )
                bytes
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException("TIDAL $label failed", error)
        }
    }

    /** Result of a segment head probe: where the audio payload starts, and the total size. */
    private class ProbeResult(val payloadOffset: Long, val totalSize: Long)

    /**
     * Probes a media segment's head with a small closed Range request. The layout is
     * [moof][mdat] with mdat last, so the payload offset is moof size + the mdat box header;
     * the total arrives in Content-Range. Widens the probe when the moof box exceeds it.
     */
    private fun probeSegment(url: String): ProbeResult {
        var probeSize = 2048L
        while (probeSize <= 1L shl 21) {
            val response =
                httpClient
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .header("User-Agent", "ArchiveTune-Android")
                            .header("Range", "bytes=0-${probeSize - 1}")
                            .get()
                            .build(),
                    ).execute()
            var head: ByteArray
            var total: Long
            response.use {
                if (it.code != 206 && !it.isSuccessful) throw IOException("TIDAL segment probe HTTP ${it.code}")
                total = it.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1L
                head = it.body?.bytes() ?: throw IOException("TIDAL segment probe was empty")
            }
            var offset = 0
            var payloadOffset = -1L
            while (offset + 8 <= head.size) {
                var size = readUInt32(head, offset)
                val headerSize = if (size == 1L) 16 else 8
                if (size == 1L && offset + 16 <= head.size) size = readUInt64(head, offset + 8)
                if (size <= 0L) throw IOException("TIDAL segment probe saw an invalid box")
                val type = String(head, offset + 4, 4, Charsets.US_ASCII)
                if (type == "mdat") {
                    payloadOffset = (offset + headerSize).toLong()
                    break
                }
                offset += size.toInt()
            }
            if (payloadOffset >= 0L && total > 0L) return ProbeResult(payloadOffset, total)
            probeSize *= 4
        }
        throw IOException("TIDAL segment probe could not locate mdat")
    }

    /**
     * Exact layout of every media segment, probed in parallel (bounded). Remux streams count
     * only the mdat payload; passthrough counts the whole segment, boxes included.
     */
    private fun buildSegmentIndex(): List<SegmentInfo> {
        val mediaUrls = segmentUrls.drop(1)
        val infos = arrayOfNulls<SegmentInfo>(mediaUrls.size)
        val errors = mutableListOf<IOException>()
        val executor: ExecutorService = Executors.newFixedThreadPool(PROBE_CONCURRENCY)
        val futures = ArrayList<Future<*>>(mediaUrls.size)
        try {
            mediaUrls.forEachIndexed { index, url ->
                futures +=
                    executor.submit {
                        try {
                            val probe = probeSegment(url)
                            val streamSize = if (flacRemux) probe.totalSize - probe.payloadOffset else probe.totalSize
                            infos[index] = SegmentInfo(url, probe.payloadOffset, streamSize, probe.totalSize)
                        } catch (error: IOException) {
                            synchronized(errors) { errors += error }
                        } catch (error: Throwable) {
                            synchronized(errors) { errors += IOException("TIDAL segment probe failed", error) }
                        }
                    }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        if (errors.isNotEmpty()) throw errors.first()
        return infos.filterNotNull().also {
            if (it.size != mediaUrls.size) throw IOException("TIDAL segment index was incomplete")
        }
    }

    private fun ensureSegmentIndex(): List<SegmentInfo> =
        segmentIndex ?: synchronized(this) {
            segmentIndex ?: buildSegmentIndex().also { segmentIndex = it }
        }

    /** Stream offset where media segment [mediaIndex]'s contribution begins. */
    private fun streamOffsetOfSegment(index: List<SegmentInfo>, mediaIndex: Int): Long {
        var offset = headerBytes.size.toLong()
        for (i in 0 until mediaIndex) offset += index[i].streamSize
        return offset
    }

    /**
     * Jumps to [absolutePosition], an exact byte offset in the synthesized stream, fetching
     * only the segment that contains it. Degrades to the sequential skip when the CDN refuses
     * Range probes so playback still resumes, just slowly.
     */
    private fun jumpToAbsolutePosition(absolutePosition: Long) {
        if (absolutePosition < headerBytes.size) {
            pendingBytes = headerBytes
            streamOffsetOfPending = 0L
            pendingOffset = absolutePosition.toInt()
            nextSegmentIndex = 1
            return
        }
        if (absolutePosition >= streamOffsetOfPending &&
            absolutePosition < streamOffsetOfPending + pendingBytes.size
        ) {
            pendingOffset = (absolutePosition - streamOffsetOfPending).toInt()
            return
        }

        val index =
            runCatching { ensureSegmentIndex() }.getOrElse {
                sequentialSkipTo(absolutePosition)
                return
            }

        var remaining = absolutePosition - headerBytes.size
        var mediaIndex = 0
        while (mediaIndex < index.size && remaining >= index[mediaIndex].streamSize) {
            remaining -= index[mediaIndex].streamSize
            mediaIndex++
        }
        if (mediaIndex >= index.size || remaining < 0L) {
            throw IOException("TIDAL progressive stream ended before the requested position")
        }
        val info = index[mediaIndex]
        Timber.tag("TidalDash").d("jump abs=%d mediaIndex=%d intra=%d payloadOffset=%d streamStart=%d", absolutePosition, mediaIndex, remaining, info.payloadOffset, streamOffsetOfSegment(index, mediaIndex))
        if (flacRemux) {
            // Range from the target byte inside the mdat payload to the segment end yields the
            // raw FLAC frames; align to the first frame sync because the seek target is the
            // extractor's constant-bitrate estimate, rarely a real boundary.
            var payload = fetchRange(info.url, from = info.payloadOffset + remaining, toInclusive = null, label = "seek segment ${mediaIndex + 1}")
            val beforeAlign = payload.size
            payload = alignToFlacFrameSync(payload)
            Timber.tag("TidalDash").d("jump fetched=%d alignShift=%d first8=%s", beforeAlign, beforeAlign - payload.size, payload.take(8).joinToString("") { "%02x".format(it) })
            pendingBytes = payload
            pendingOffset = 0
            streamOffsetOfPending = streamOffsetOfSegment(index, mediaIndex) + remaining
        } else {
            // Passthrough must start at a box boundary for the fMP4 extractor: fetch the whole
            // segment and skip the intra-segment offset in memory.
            val segment = fetchRange(info.url, from = 0, toInclusive = null, label = "seek segment ${mediaIndex + 1}")
            pendingBytes = segment
            streamOffsetOfPending = streamOffsetOfSegment(index, mediaIndex)
            pendingOffset = remaining.toInt()
        }
        nextSegmentIndex = mediaIndex + 1
    }

    /**
     * FLAC frame headers start with the 14-bit sync 0b11111111111110 plus a reserved 0 bit:
     * 0xFF followed by 0b111110xx. Skipping to the first plausible boundary keeps the decoder
     * from ingesting a partial frame; the sub-frame shift is imperceptible.
     */
    private fun alignToFlacFrameSync(payload: ByteArray): ByteArray {
        val limit = minOf(payload.size - 2, 1 shl 16)
        for (i in 0 until limit) {
            if (payload[i] == 0xff.toByte() && (payload[i + 1].toInt() and 0xFC) == 0xF8) {
                return if (i == 0) payload else payload.copyOfRange(i, payload.size)
            }
        }
        return payload
    }

    /** Sequential skip for CDNs without Range: downloads through to the target byte. */
    private fun sequentialSkipTo(absolutePosition: Long) {
        var remaining = absolutePosition
        if (remaining >= streamOffsetOfPending && remaining < streamOffsetOfPending + pendingBytes.size) {
            pendingOffset = (remaining - streamOffsetOfPending).toInt()
            return
        }
        remaining -= headerBytes.size
        pendingBytes = ByteArray(0)
        pendingOffset = 0
        streamOffsetOfPending = headerBytes.size.toLong()
        while (remaining > 0L && nextSegmentIndex < segmentUrls.size) {
            val payload = fetchSegment(segmentUrls[nextSegmentIndex], nextSegmentIndex)
            nextSegmentIndex++
            if (remaining < payload.size) {
                pendingBytes = payload
                pendingOffset = remaining.toInt()
                remaining = 0L
                break
            } else {
                remaining -= payload.size
                streamOffsetOfPending += payload.size.toLong()
            }
        }
        if (remaining > 0L) {
            throw IOException("TIDAL progressive stream ended before the requested position")
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun readUInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xff)
        return value
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        segmentUrls = emptyList()
        nextSegmentIndex = 0
        pendingBytes = ByteArray(0)
        pendingOffset = 0
        bytesRemaining = C.LENGTH_UNSET.toLong()
        currentUri = null
        segmentIndex = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(
        private val httpClient: OkHttpClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TidalProgressiveDashDataSource(httpClient)
    }

    companion object {
        private val MANIFEST_CODEC = Regex("""codecs="([^"]+)"""")
        private val MANIFEST_TIMESCALE = Regex("""timescale="(\d+)""")
        private val MANIFEST_SAMPLERATE = Regex("""audioSamplingRate="(\d+)""")
        private val SEGMENT_TIMELINE_ENTRY = Regex("""<S\s+d="(\d+)"(?:\s+r="(\d+)")?""")
        private const val PROBE_CONCURRENCY = 8
    }
}
