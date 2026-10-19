/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("isDolbyAtmos") val isDolbyAtmos: Boolean = false,
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("bitDepth") val bitDepth: Int? = null,
    @SerialName("sampleRate") val sampleRate: Double? = null,
    @SerialName("directStreamUrl") val directStreamUrl: String? = null,
)

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("dataUrl") val dataUrl: String? = null,
    @SerialName("manifestXml") val manifestXml: String? = null,
    @SerialName("format") val format: String = "flac",
    @SerialName("codec") val codec: String = "",
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("sampleRate") val sampleRate: Double? = null,
    @SerialName("bitDepth") val bitDepth: Int? = null,
    @SerialName("isDolbyAtmos") val isDolbyAtmos: Boolean = false,
    @SerialName("audioMode") val audioMode: String = "",
    @SerialName("quality") val quality: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
)

@Serializable
data class AddonManifest(
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "1.0.0",
    @SerialName("description") val description: String = "",
    @SerialName("author") val author: String = "",
)
