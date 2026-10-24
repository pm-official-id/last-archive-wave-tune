/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.tidal

import kotlin.math.roundToLong

/**
 * Pure-Kotlin DASH MPD fixups, kept free of Android imports so they are unit-testable.
 *
 * Ported from native LastWave's SegmentedMpdBuilder.sanitizeMpdXml, where it fixed
 * seeking on the same addon manifests this fork plays.
 */
object DashManifestSanitizer {
    /**
     * Converts floating-point `timescale`/`duration` attributes on `<SegmentList>`/`<SegmentTemplate>`
     * to integer units. Media3's DashManifestParser reads both via `Long.parseLong`, so a decimal
     * like `duration="10.000"` throws NumberFormatException and seeking resets to position 0 —
     * the exact "progress bar seek broken" failure the addon's manifests can produce. Durations
     * without a timescale are converted to milliseconds under `timescale="1000"`.
     */
    fun sanitize(rawXml: String): String {
        if (!rawXml.contains("duration", ignoreCase = true) && !rawXml.contains("timescale", ignoreCase = true)) return rawXml

        val floatTimescaleRegex = Regex(
            """(<(?:SegmentList|SegmentTemplate)[^>]*?)\btimescale\s*=\s*['"](\d+)\.(\d+)['"]([^>]*?>)""",
            RegexOption.IGNORE_CASE,
        )
        var xml = floatTimescaleRegex.replace(rawXml) { match ->
            val floatVal = "${match.groupValues[2]}.${match.groupValues[3]}".toDoubleOrNull() ?: return@replace match.value
            "${match.groupValues[1]}timescale=\"${floatVal.roundToLong().coerceAtLeast(1L)}\"${match.groupValues[4]}"
        }

        val floatDurationRegex = Regex(
            """(<(?:SegmentList|SegmentTemplate)[^>]*?)\bduration\s*=\s*['"](\d+)\.(\d+)['"]([^>]*?>)""",
            RegexOption.IGNORE_CASE,
        )
        xml = floatDurationRegex.replace(xml) { match ->
            val prefix = match.groupValues[1]
            val suffix = match.groupValues[4]
            val floatVal = "${match.groupValues[2]}.${match.groupValues[3]}".toDoubleOrNull() ?: return@replace match.value
            if ((prefix + suffix).contains("timescale", ignoreCase = true)) {
                "${prefix}duration=\"${floatVal.roundToLong().coerceAtLeast(1L)}\"$suffix"
            } else {
                "${prefix}timescale=\"1000\" duration=\"${(floatVal * 1000.0).roundToLong().coerceAtLeast(1L)}\"$suffix"
            }
        }
        return xml
    }
}
