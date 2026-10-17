/*
 * ArchiveTune (2026)
 * Ported from LastWave-Native under GPL-3.0
 */

package moe.rukamori.archivetune.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddonManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
) {
    val displayName: String
        get() = name.ifBlank { id }
}

@Serializable
data class AddonSearchResponse(
    @SerialName("tracks") val tracks: List<AddonTrack> = emptyList(),
)

@Serializable
data class AddonTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("format") val format: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("quality") val quality: String = "",
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("bitDepth") val rawBitDepth: Int? = null,
    @SerialName("sampleRate") val rawSampleRate: Double? = null,
    @SerialName("streamURL") val streamURL: String? = null,
    @SerialName("streamUrl") val streamUrlCamel: String? = null,
) {
    val directStreamUrl: String?
        get() = streamURL?.takeIf { it.isNotBlank() } ?: streamUrlCamel?.takeIf { it.isNotBlank() }

    val bitDepth: Int?
        get() = rawBitDepth ?: parseDepthFromQualityString(audioQuality) ?: parseDepthFromQualityString(quality) ?: parseDepthFromQualityString(format)

    val sampleRate: Double?
        get() = rawSampleRate ?: parseRateFromQualityString(audioQuality) ?: parseRateFromQualityString(quality) ?: parseRateFromQualityString(format)

    val isDolbyAtmos: Boolean
        get() = atmos || "$audioQuality $quality $format".contains("atmos", ignoreCase = true) || "$audioQuality $quality $format".contains("dolby", ignoreCase = true)
}

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("dataUrl") val dataUrl: String? = null,
    @SerialName("format") val format: String = "flac",
    @SerialName("codec") val codec: String = "flac",
    @SerialName("quality") val quality: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("sampleRate") val rawSampleRate: Double? = null,
    @SerialName("bitDepth") val rawBitDepth: Int? = null,
    @SerialName("atmos") val atmos: Boolean = false,
) {
    val directStreamUrl: String?
        get() = url.takeIf { it.isNotBlank() } ?: dataUrl?.takeIf { it.isNotBlank() }

    val bitDepth: Int?
        get() = rawBitDepth ?: parseDepthFromQualityString(audioQuality) ?: parseDepthFromQualityString(quality) ?: parseDepthFromQualityString(format)

    val sampleRate: Double?
        get() = rawSampleRate ?: parseRateFromQualityString(audioQuality) ?: parseRateFromQualityString(quality) ?: parseRateFromQualityString(format)

    val isDolbyAtmos: Boolean
        get() = atmos || "$audioQuality $quality $format".contains("atmos", ignoreCase = true) || "$audioQuality $quality $format".contains("dolby", ignoreCase = true)
}

internal fun parseDepthFromQualityString(s: String): Int? {
    if (s.isBlank()) return null
    if (s.contains("24") || s.contains("hi_res", ignoreCase = true) || s.contains("hires", ignoreCase = true)) return 24
    if (s.contains("16") || s.contains("cd", ignoreCase = true) || s.contains("lossless", ignoreCase = true)) return 16
    return null
}

internal fun parseRateFromQualityString(s: String): Double? {
    if (s.isBlank()) return null
    if (s.contains("192")) return 192000.0
    if (s.contains("96")) return 96000.0
    if (s.contains("48")) return 48000.0
    if (s.contains("44")) return 44100.0
    return null
}
