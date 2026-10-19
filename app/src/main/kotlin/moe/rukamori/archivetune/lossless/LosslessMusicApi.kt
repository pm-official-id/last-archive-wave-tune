/*
 * ArchiveTune (2026)
 * Ported from LastWave-Native under GPL-3.0
 */

package moe.rukamori.archivetune.lossless

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.addon.AddonClient
import moe.rukamori.archivetune.constants.LastWaveEnabledKey
import moe.rukamori.archivetune.constants.LastWaveStreamingAddonUrlKey
import moe.rukamori.archivetune.constants.LastWaveStreamingQualityKey
import moe.rukamori.archivetune.utils.get
import okhttp3.OkHttpClient
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "application/dash+xml",
    val bitDepth: Int = 0,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
    val audioCodecOverride: String? = null,
)

private data class TidalCandidateItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val performerName: String = "",
    val albumArtistName: String = "",
    val albumTitle: String = "",
    val performers: String = "",
    val isAtmos: Boolean = false,
    val isSpatial: Boolean = false,
    val rawAddonId: String = "",
    val audioQuality: String = "",
    val bitDepth: Int? = null,
    val directStreamUrl: String? = null,
) {
    fun isHiResFlagged(): Boolean =
        (bitDepth ?: 0) > 16 ||
            audioQuality.contains("HI_RES", ignoreCase = true) ||
            audioQuality.contains("HI-RES", ignoreCase = true) ||
            audioQuality.contains("24-BIT", ignoreCase = true) ||
            audioQuality.contains("24BIT", ignoreCase = true) ||
            audioQuality.contains("24/")
}

class LosslessMusicApi(
    private val context: Context,
    okHttpClient: OkHttpClient,
    private val dataStore: DataStore<Preferences>,
) {
    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    companion object {
        const val QUALITY_DOLBY_ATMOS = 28
        const val QUALITY_MAX_HI_RES = 27
        const val QUALITY_HI_RES_96 = 7
        const val QUALITY_CD_LOSSLESS = 6
        const val QUALITY_MP3_320 = 5
        const val QUALITY_DATA_SAVER = 4
        const val QUALITY_YOUTUBE = -1

        private const val TAG = "LosslessMusicApi"
        private val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")
        private val MANIFEST_SAMPLE_RATE = Regex("""audioSamplingRate="(\d+)"""", RegexOption.IGNORE_CASE)
        private val MANIFEST_RENDITION = Regex("""Representation[^>]*id="[^"]*,(\d+),(\d+)"""", RegexOption.IGNORE_CASE)

        fun manifestCodecOf(dataUrl: String): String? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            if (b64.isEmpty()) return null
            return runCatching {
                val xml = String(
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                    Charsets.UTF_8,
                ).lowercase()
                MANIFEST_CODECS.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        fun manifestSampleRateOf(dataUrl: String): Int? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
                runCatching {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            } else if (dataUrl.trimStart().startsWith("<")) {
                dataUrl
            } else null
            if (xml.isNullOrBlank()) return null
            return MANIFEST_SAMPLE_RATE.find(xml)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        }

        fun manifestBitDepthOf(dataUrl: String): Int? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            val xml = if (b64.isNotEmpty() && dataUrl.startsWith("data:application/dash+xml")) {
                runCatching {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
            } else if (dataUrl.trimStart().startsWith("<")) {
                dataUrl
            } else null
            if (xml.isNullOrBlank()) return null
            return MANIFEST_RENDITION.find(xml)?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 8..32 }
        }

        fun isAtmosCodec(codec: String?): Boolean {
            val c = codec?.trim()?.lowercase().orEmpty()
            return c.startsWith("ec-3") || c.startsWith("eac3") || c.startsWith("ac-3")
        }

        fun isAtmosStreamUrl(url: String): Boolean {
            if (url.startsWith("data:application/dash+xml")) {
                return isAtmosCodec(manifestCodecOf(url))
            }
            val lower = url.lowercase()
            return lower.contains("atmos") || lower.contains("eac3") || lower.contains("ec-3")
        }

        private val ARTIST_SEPARATORS = Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)
        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")

        fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.ROOT)
            .replace("$", "s")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(MULTI_SPACE, " ")
            .trim()

        fun cleanForSearch(raw: String): String = normalizeText(raw)
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int? = null,
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        val addonUrl = dataStore.get(LastWaveStreamingAddonUrlKey, "")
        val lastWaveEnabled = dataStore.get(LastWaveEnabledKey, true)
        if (!lastWaveEnabled || addonUrl.isBlank()) return@withContext null

        val selectedQualityFormat = preferredQuality ?: dataStore.get(LastWaveStreamingQualityKey, QUALITY_MAX_HI_RES)
        if (selectedQualityFormat == QUALITY_YOUTUBE) return@withContext null

        Log.i(TAG, "resolveStream starting for '$title' by '$artist' via addon (url=$addonUrl, qualityFormat=$selectedQualityFormat)")

        return@withContext resolveFromAddon(
            title = title,
            artist = artist,
            expectedDurationSeconds = expectedDurationSeconds,
            expectedAlbum = expectedAlbum,
            preferredQuality = selectedQualityFormat,
            addonBaseUrl = addonUrl,
        )
    }

    private suspend fun resolveFromAddon(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        preferredQuality: Int,
        addonBaseUrl: String,
    ): LosslessAudioStream? {
        val addonClient = AddonClient(addonBaseUrl, client)
        val cleanArtist = cleanForSearch(artist).ifBlank { artist }
        val cleanTitle = cleanForSearch(title).ifBlank { title }

        val artistList = cleanArtist.split(ARTIST_SEPARATORS)
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }
        val primaryArtist = artistList.firstOrNull().orEmpty()
        val secondaryArtist = artistList.getOrNull(1).orEmpty()

        val queries = listOfNotNull(
            if (primaryArtist.isNotBlank()) "$cleanTitle $primaryArtist".trim() else null,
            if (secondaryArtist.isNotBlank()) "$cleanTitle $secondaryArtist".trim() else null,
            cleanTitle.trim(),
            if (cleanArtist.isNotBlank() && cleanArtist != primaryArtist) "$cleanTitle $cleanArtist".trim() else null,
        ).filter { it.isNotBlank() }.distinct()

        val isAtmosPreferred = preferredQuality == QUALITY_DOLBY_ATMOS
        val qualityParam = when (preferredQuality) {
            QUALITY_DOLBY_ATMOS -> "lossless"
            QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> "hi_res"
            QUALITY_CD_LOSSLESS -> "lossless"
            QUALITY_MP3_320 -> "high"
            QUALITY_DATA_SAVER -> "low"
            else -> "lossless"
        }

        var candidates: List<TidalCandidateItem> = emptyList()
        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val searchResult = addonClient.search(query, qualityParam, isAtmosPreferred)
            val tracks = searchResult.getOrNull() ?: continue
            if (tracks.isEmpty()) continue

            val verified = tracks.map { track ->
                TidalCandidateItem(
                    id = track.id.toLongOrNull() ?: track.id.hashCode().toLong(),
                    title = track.title,
                    duration = track.duration.toInt(),
                    performerName = track.artist,
                    albumArtistName = track.artist,
                    albumTitle = track.album,
                    performers = track.artist,
                    isAtmos = track.isDolbyAtmos || track.atmos,
                    rawAddonId = track.id,
                    audioQuality = track.audioQuality,
                    bitDepth = track.bitDepth,
                    directStreamUrl = track.directStreamUrl,
                )
            }.filter { candidate ->
                val durationDiff = if (expectedDurationSeconds != null && candidate.duration > 0) {
                    kotlin.math.abs(candidate.duration - expectedDurationSeconds)
                } else 0
                durationDiff <= 20
            }

            if (verified.isNotEmpty()) {
                candidates = verified
                break
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "resolveFromAddon: No matching candidate found for '$title' by '$artist'")
            return null
        }

        val wantAtmos = isAtmosPreferred
        val wantsHiRes = preferredQuality == QUALITY_MAX_HI_RES || preferredQuality == QUALITY_HI_RES_96

        for (candidate in candidates.take(4)) {
            currentCoroutineContext().ensureActive()
            val trackId = candidate.rawAddonId.ifBlank { candidate.id.toString() }
            val streamResult = addonClient.stream(trackId, qualityParam, wantAtmos)
            val stream = streamResult.getOrNull()

            val rawUrl = stream?.dataUrl?.takeIf { it.isNotBlank() }
                ?: stream?.url?.takeIf { it.isNotBlank() }
                ?: candidate.directStreamUrl?.takeIf { it.isNotBlank() }
                ?: continue

            val isStreamAtmos = stream?.isDolbyAtmos == true || isAtmosCodec(stream?.codec) || isAtmosStreamUrl(rawUrl)
            val manifestSampleRate = manifestSampleRateOf(rawUrl)
            val manifestDepth = manifestBitDepthOf(rawUrl)
            val streamSampleRate = stream?.sampleRate ?: 0.0
            val rawSampleRate = if (streamSampleRate > 1000.0) streamSampleRate else if (streamSampleRate > 0.0) streamSampleRate * 1000.0 else 44100.0
            val effectiveSampleRate = manifestSampleRate?.toDouble() ?: rawSampleRate

            val reportedDepth = manifestDepth ?: stream?.bitDepth?.takeIf { it > 0 } ?: candidate.bitDepth?.takeIf { it > 0 }
            val effectiveBitDepth = when {
                (reportedDepth ?: 0) > 0 -> reportedDepth
                effectiveSampleRate > 192000.0 -> 32
                wantsHiRes -> 24
                else -> null
            }

            val formatId = when {
                isStreamAtmos -> QUALITY_DOLBY_ATMOS
                (effectiveBitDepth ?: 0) > 16 || effectiveSampleRate > 48000.0 -> QUALITY_MAX_HI_RES
                else -> QUALITY_CD_LOSSLESS
            }

            val lowerUrlPath = rawUrl.substringBefore('?').lowercase()
            val streamMimeType = when {
                rawUrl.startsWith("data:application/dash+xml") -> "application/dash+xml"
                lowerUrlPath.endsWith(".mpd") -> "application/dash+xml"
                isStreamAtmos -> "audio/mp4"
                lowerUrlPath.endsWith(".flac") -> "audio/flac"
                lowerUrlPath.endsWith(".mp4") || lowerUrlPath.endsWith(".m4a") -> "audio/mp4"
                else -> "application/dash+xml"
            }

            return LosslessAudioStream(
                url = rawUrl,
                mimeType = streamMimeType,
                bitDepth = effectiveBitDepth ?: 16,
                samplingRate = effectiveSampleRate / 1000.0,
                formatId = formatId,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
            )
        }
        return null
    }
}
