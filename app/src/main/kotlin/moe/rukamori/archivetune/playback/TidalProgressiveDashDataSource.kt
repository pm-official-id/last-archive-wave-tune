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
import java.io.File
import java.io.IOException

/** Presents TIDAL's fragmented FLAC DASH stream as a progressive FLAC stream. *
 * Non-FLAC renditions (Dolby Atmos E-AC-3 JOC, AAC) cannot survive the FLAC remux —
 * the synthesized `fLaC` header plus raw E-AC-3/AAC frames is unparseable. For those
 * the stream is passed through as plain fragmented MP4 (init + media segments
 * concatenated) so ExoPlayer's FragmentedMp4Extractor reads the real track format
 * and picks the correct decoder. */
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
        pendingBytes =
            if (flacRemux) {
                byteArrayOf(
                    'f'.code.toByte(),
                    'L'.code.toByte(),
                    'a'.code.toByte(),
                    'C'.code.toByte(),
                ) + fetchSegment(urls.first(), 0)
            } else {
                fetchSegment(urls.first(), 0)
            }
        pendingOffset = 0
        val estimatedTotalBytes = pendingBytes.size.toLong() + (urls.size - 1).toLong() * 200_000L
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (estimatedTotalBytes - dataSpec.position).coerceAtLeast(1L)
        }
        opened = true
        transferStarted(dataSpec)

        try {
            if (dataSpec.position > 0L) seekToPosition(dataSpec.position)
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
        val segmentIndex = nextSegmentIndex
        val payload = fetchSegment(segmentUrls[segmentIndex], segmentIndex)
        nextSegmentIndex++
        pendingBytes = payload
        pendingOffset = 0
        return pendingBytes.isNotEmpty()
    }

    private fun fetchSegment(
        url: String,
        segmentIndex: Int,
    ): ByteArray {
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "audio/flac,audio/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "ArchiveTune-Android")
                .get()
                .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("TIDAL FLAC segment ${segmentIndex + 1} HTTP ${response.code}")
                }
                val bytes = response.body?.bytes() ?: throw IOException("TIDAL FLAC segment was empty")
                if (!flacRemux) {
                    // Passthrough: the concatenated init + fMP4 media segments form a valid
                    // fragmented-MP4 stream on their own; unwrapping would destroy the track.
                    bytes
                } else if (segmentIndex == 0) {
                    TidalAudioProvider.progressiveDashFlacMetadata(bytes)
                } else {
                    TidalAudioProvider.progressiveDashAudioPayload(bytes)
                }
            }
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException("TIDAL FLAC segment ${segmentIndex + 1} failed", error)
        }
    }

    private fun seekToPosition(bytesToSkip: Long) {
        var remaining = bytesToSkip
        if (remaining <= pendingBytes.size) {
            pendingOffset = remaining.toInt()
            return
        }

        remaining -= pendingBytes.size
        pendingBytes = ByteArray(0)
        pendingOffset = 0

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
            }
        }

        if (remaining > 0L) {
            throw IOException("TIDAL progressive stream ended before the requested position")
        }
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
    }
}
