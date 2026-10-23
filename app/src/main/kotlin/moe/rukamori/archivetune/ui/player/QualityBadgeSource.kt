/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.ShowSourceInQualityBadgeKey
import moe.rukamori.archivetune.utils.rememberPreference

private fun AudioSourceType.badgeName(): String =
    when (this) {
        AudioSourceType.LASTWAVE -> "LASTWAVE"
        AudioSourceType.TIDAL -> "TIDAL"
        AudioSourceType.QOBUZ -> "QOBUZ"
        AudioSourceType.QOBUZ_BACKUP -> "QOBUZ BACKUP"
        AudioSourceType.DEEZER -> "DEEZER"
        AudioSourceType.APPLE -> "APPLE MUSIC"
        AudioSourceType.AMAZON -> "AMAZON"
        AudioSourceType.QQ -> "QQ MUSIC"
        AudioSourceType.JIOSAAVN -> "JIOSAAVN"
        AudioSourceType.YOUTUBE -> "YOUTUBE"
    }

/**
 * Prefixes [codec] with the source actually feeding playback when the "show source in quality
 * badge" preference is on and the source is known: "LASTWAVE · LOSSLESS", "LASTWAVE · DOLBY
 * ATMOS", "YOUTUBE · OPUS". Every player style's quality chip funnels through here so they all
 * label the same thing.
 */
@Composable
fun qualityBadgeText(codec: String): String {
    val playerConnection = LocalPlayerConnection.current ?: return codec
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val mediaId = mediaMetadata?.id ?: return codec
    val revision by playerConnection.service.activeSourceRevision.collectAsStateWithLifecycle()
    val (showSourceInBadge, _) = rememberPreference(ShowSourceInQualityBadgeKey, true)
    val activeSource =
        remember(mediaId, revision) {
            playerConnection.service.activeSourceForSong(mediaId)
        }
    if (!showSourceInBadge) return codec
    val sourceTag = activeSource?.badgeName() ?: return codec
    return "$sourceTag · $codec"
}
