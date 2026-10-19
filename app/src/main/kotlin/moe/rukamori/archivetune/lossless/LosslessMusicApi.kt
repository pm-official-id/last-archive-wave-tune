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
import moe.rukamori.archivetune.constants.DEFAULT_LASTWAVE_ADDON_URL
import moe.rukamori.archivetune.constants.LastWaveEnabledKey
import moe.rukamori.archivetune.constants.LastWaveStreamingAddonUrlKey
import moe.rukamori.archivetune.constants.LastWaveStreamingQualityKey
import moe.rukamori.archivetune.utils.get
import okhttp3.OkHttpClient
import org.json.JSONObject
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

        fun isDecoyStream(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val lower = url.lowercase()
            return lower.contains("pranks-cdn") || lower.contains("definatelynagato")
        }

        private val LANGUAGE_TOKENS = setOf(
            "telugu", "tamil", "hindi", "kannada", "malayalam", "punjabi",
            "marathi", "gujarati", "bengali", "bhojpuri", "odia", "oriya",
            "assamese", "urdu", "sanskrit", "english", "spanish", "french",
            "german", "italian", "portuguese", "japanese", "korean", "chinese",
            "arabic", "turkish",
        )

        data class TitleParts(
            val words: List<String>,
            val core: String,
            val versions: Set<String>,
            val context: Set<String>,
        )

        private val VERSION_WORDS = setOf(
            "remix", "remixes", "rmx", "refix", "flip", "bootleg", "mashup", "medley",
            "live", "concert", "unplugged", "acoustic", "instrumental", "karaoke",
            "vocals", "vocal", "acapella", "acappella", "backing", "stems", "stem",
            "cover", "demo", "reprise", "remake", "rework", "extended", "edit",
            "sped", "slowed", "reverb", "nightcore", "lofi", "orchestral", "symphonic",
            "part", "pt", "chapter", "atmos", "dolby", "spatial",
        )

        private val NEUTRAL_SEGMENTS = setOf(
            "albumversion", "originalversion", "originalmix", "singleversion",
            "radioversion", "radioedit", "stereoversion", "monoversion",
            "studioversion", "fullversion", "standardversion", "explicitversion",
            "deluxeversion", "originaltrack", "audio", "officialaudio", "officialvideo",
            "musicvideo", "lyricvideo", "lyrics", "lyric", "visualizer", "4k", "hd",
        )

        private val NOISE_WORDS = setOf(
            "official", "video", "audio", "lyrics", "lyric", "lyrical", "visualizer",
            "song", "songs", "full", "music", "the", "and", "from", "feat", "ft",
            "featuring", "with", "new", "latest", "free", "download", "remaster",
            "remastered", "explicit", "clean", "bonus", "track", "deluxe", "original",
            "album", "single", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv",
            "version", "versions", "mix", "mixes",
        )

        private val RECORD_LABELS = setOf(
            "tseries", "t series", "zeemusiccompany", "zeemusic", "zee music", "zee music company",
            "sonymusicindia", "sonymusic", "sony music", "sony music india",
            "yrf", "yashrajfilms", "tips", "tipsmusic", "tips official",
            "venus", "speedrecords", "speed records", "saregama", "geetmp3", "geet mp3",
            "whitehillmusic", "white hill music", "vyrloriginals", "vyrl", "desimusicfactory",
            "eros", "erosnow", "adityamusic", "aditya music", "laharimusic", "lahari music",
            "t-series", "sony", "universal", "warner",
        )

        private fun isRecordLabel(artist: String): Boolean {
            val norm = normalizeText(artist).replace(" ", "")
            return RECORD_LABELS.any { norm == it.replace(" ", "") || (it.length >= 4 && norm.contains(it.replace(" ", ""))) }
        }

        private val TRAILING_NOISE = setOf(
            "song", "songs", "video", "audio", "lyrics", "lyric", "lyrical",
            "official", "full", "hd", "hq", "4k", "mp3", "ost", "soundtrack", "mv", "track",
        )

        private val JOINING_WORDS = setOf("and")
        private val ARTIST_SEPARATORS =
            Regex("""\s*(?:[,&/;·|]|\band\b|\bx\b|\bvs\.?\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*""", RegexOption.IGNORE_CASE)

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

        private val TOPIC_CHANNEL_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
        private val PIPE_NOISE = Regex("""\s*\|.*$""")
        private val SOUNDTRACK_SUFFIX = Regex(
            """(?i)\s*[\[(]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*(?:["“][^"”\r\n]+["”]|[^\])]+)?\s*[\])]\s*$|\s*[-–—|]\s*(?:from\s+(?:the\s+)?(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)|soundtrack|ost)\s*.*$""",
        )
        private val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring|with)\s+.*$""")
        private val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+)|remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|special\s*edition|anniversary(?:\s*edition)?|radio\s*edit|single\s*version|album\s*version|with\s+[^\])]+|from\s+[^\])]+)\s*[\])]""",
        )
        private val TRAILING_DISPLAY_NOISE = Regex(
            """(?i)\s*[-–—|]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song|coke\s*studio(?:\s*season\s*\d+)?|a\s*colors\s*show|tiny\s*desk|live\s*session|unplugged|acoustic\s*version)\s*$""",
        )
        private val VERSION_NOISE_REGEX = Regex(
            """(?i)\s*[\[(]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|special\s*edition|original\s*mix|extended\s*mix|club\s*mix|acoustic|live(?:\s*at[^\])]*)?|edit|version|mono|stereo|clean|explicit|re-?recorded|pt\.?\s*\d+|part\s*\d+|from\s+[^\])]+)\s*[\])]|\s*[-–—|]\s*(?:remaster(?:ed)?(?:\s*\d{2,4})?|\d{2,4}\s*remaster(?:ed)?|deluxe(?:\s*edition)?|bonus(?:\s*track)?|radio\s*edit|single\s*version|album\s*version|anniversary(?:\s*edition)?|live(?:\s*at.*)?|acoustic|re-?recorded|mono|stereo|coke\s*studio.*|a\s*colors\s*show)\s*$""",
        )
        private val ATMOS_VERSION_MARKER = Regex(
            """(?i)[\[(]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial|360(?:\s*reality\s*audio)?|sony\s+360|mpeg-?\s*h)\s*[\])]|\s*[-–—]\s*(?:dolby\s+atmos|dolby|atmos|spatial\s+audio|spatial)\s*$""",
        )

        fun parseTitle(raw: String, artist: String = "", album: String? = null): TitleParts {
            val versions = sortedSetOf<String>()
            val context = mutableSetOf<String>()
            var text = raw.lowercase(Locale.ROOT)
                .replace("&", " and ")
                .replace("$", "s")

            val bracketRegex = Regex("""[(\[]([^()\[\]]*)[)\]]""")
            repeat(3) {
                if (!bracketRegex.containsMatchIn(text)) return@repeat
                text = bracketRegex.replace(text) { match ->
                    classifySegment(match.groupValues[1], versions, context)
                    " "
                }
            }
            text.indexOfFirst { it == '(' || it == '[' }.takeIf { it >= 0 }?.let { open ->
                classifySegment(text.substring(open), versions, context)
                text = text.substring(0, open)
            }

            val dashRegex = Regex("""\s*[-–—:|]+\s*""")
            repeat(3) {
                val dash = dashRegex.find(text) ?: return@repeat
                val head = text.substring(0, dash.range.first).trim()
                val tail = text.substring(dash.range.last + 1).trim()
                if (head.isBlank() || tail.isBlank()) return@repeat

                if (isArtistOrAlbumName(head, artist, album)) {
                    classifySegment(head, versions, context)
                    text = tail
                } else if (isArtistOrAlbumName(tail, artist, album)) {
                    classifySegment(tail, versions, context)
                    text = head
                } else if (isSegmentMetadata(tail)) {
                    classifySegment(tail, versions, context)
                    text = head
                } else if (isSegmentMetadata(head)) {
                    classifySegment(head, versions, context)
                    text = tail
                } else {
                    classifySegment(head, versions, context)
                    text = tail
                }
            }

            text = text.replace(Regex("""\b(feat|ft|featuring|with)\b.*""", RegexOption.IGNORE_CASE), " ")

            var words = text.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() && it !in JOINING_WORDS }

            while (words.size > 1 && words.last() in TRAILING_NOISE) {
                words = words.dropLast(1)
            }

            return TitleParts(
                words = words,
                core = words.joinToString(""),
                versions = versions,
                context = context,
            )
        }

        private fun classifySegment(
            segment: String,
            versions: MutableSet<String>,
            context: MutableSet<String>,
        ) {
            val words = segment.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            if (words.joinToString("") in NEUTRAL_SEGMENTS) return
            val marks = words.filter { it in VERSION_WORDS }
            if (marks.isNotEmpty()) {
                versions += marks
                return
            }
            context += words.filter { it.length > 2 && it !in NOISE_WORDS }
        }

        private fun isSegmentMetadata(segment: String): Boolean {
            val words = segment.split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
            if (words.isEmpty()) return true
            if (words.joinToString("") in NEUTRAL_SEGMENTS) return true
            if (words.any { it in VERSION_WORDS }) return true
            return words.all { it in NOISE_WORDS || it in NEUTRAL_SEGMENTS || it in VERSION_WORDS }
        }

        private fun isArtistOrAlbumName(text: String, artist: String, album: String?): Boolean {
            if (text.isBlank()) return false
            val words = text.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return false

            val artistWords = artist.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
                .map { it.replace(NON_ALPHANUMERIC, "") }
                .filter { it.isNotEmpty() }
                .toSet()
            if (words.all { it in artistWords } || (artistWords.isNotEmpty() && artistWords.all { it in words })) return true

            for (part in text.split(ARTIST_SEPARATORS)) {
                val partWords = part.split(Regex("""[\s.·/]+""")).map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
                if (partWords.isNotEmpty() && artistWords.isNotEmpty() &&
                    (partWords.all { it in artistWords } || artistWords.all { it in partWords })) {
                    return true
                }
            }

            if (!album.isNullOrBlank()) {
                val albumWords = album.lowercase(Locale.ROOT).split(Regex("""[\s.·/]+"""))
                    .map { it.replace(NON_ALPHANUMERIC, "") }
                    .filter { it.isNotEmpty() }
                    .toSet()
                if (words.all { it in albumWords } || (albumWords.isNotEmpty() && albumWords.all { it in words })) return true
            }

            return false
        }

        private val ARTIST_NOISE_WORDS = setOf("the", "and", "feat", "ft", "featuring", "with", "x", "topic")
        private val PERFORMING_ROLE_WORDS = setOf(
            "mainartist", "featuredartist", "performer", "vocal", "vocals", "vocalist", "singer",
        )
        private val IDENTITY_VARIANT_PATTERNS = listOf(
            "live" to Regex("\\blive\\b"),
            "acoustic" to Regex("\\bacoustic\\b"),
            "karaoke" to Regex("\\bkaraoke\\b"),
            "instrumental" to Regex("\\binstrumental\\b"),
            "tribute" to Regex("\\btribute\\b"),
            "cover" to Regex("\\bcover\\b"),
            "remix" to Regex("\\bremix(?:ed)?\\b"),
            "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
            "demo" to Regex("\\bdemo\\b"),
            "slowed" to Regex("\\bslowed\\b"),
            "reverb" to Regex("\\breverb\\b"),
            "sped-up" to Regex("\\bsped up\\b"),
            "nightcore" to Regex("\\bnightcore\\b"),
            "radio-edit" to Regex("\\bradio edit\\b"),
            "extended" to Regex("\\bextended(?: version| mix)?\\b"),
            "atmos" to Regex("\\bdolby\\b|\\batmos\\b|\\bspatial\\b|360\\s*reality|sony\\s*360"),
        )

        private fun verifiedMatchScore(
            item: TidalCandidateItem,
            title: String,
            artist: String,
            expectedDurationSeconds: Int?,
            expectedAlbum: String?,
        ): Int? {
            val matchArtist = cleanForSearch(artist).ifBlank { artist }
            val wanted = parseTitle(title, matchArtist, expectedAlbum)
            val got = parseTitle(item.title, item.performerName, item.albumTitle)
            if (wanted.core.isBlank() || got.core.isBlank()) return null

            if (wanted.versions != got.versions) {
                Log.d(TAG, "reject candidate id=${item.id}: version mismatch wanted=${wanted.versions} got=${got.versions} for '$title'")
                return null
            }

            val targetVariants = identityVariants(title, matchArtist)
            val candidateVariants = identityVariants(item.title, matchArtist)
            val severeMismatch = (!targetVariants.contains("instrumental") && candidateVariants.contains("instrumental")) ||
                (!targetVariants.contains("karaoke") && candidateVariants.contains("karaoke")) ||
                (!targetVariants.contains("tribute") && candidateVariants.contains("tribute")) ||
                (!targetVariants.contains("cover") && candidateVariants.contains("cover"))
            if (severeMismatch) {
                Log.d(TAG, "reject candidate id=${item.id}: severe variant mismatch target=$targetVariants candidate=$candidateVariants for '$title'")
                return null
            }

            val isExactCore = wanted.core == got.core
            val isNormalizedCore = wanted.core.replace(" ", "") == got.core.replace(" ", "")
            val isPhoneticCore = foldTransliteration(wanted.core) == foldTransliteration(got.core)
            val coreDistance = levenshtein(wanted.core, got.core)
            val minLen = minOf(wanted.core.length, got.core.length)
            val isFuzzyCore = minLen >= 7 && coreDistance <= (if (minLen >= 14) 2 else 1)

            if (!isExactCore && !isNormalizedCore && !isPhoneticCore && !isFuzzyCore) return null

            val hasArtistMatch = isVerifiedArtistMatch(
                targetArtist = matchArtist,
                performer = item.performerName,
                albumArtist = item.albumArtistName,
                performersText = item.performers,
            )

            val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0 && item.duration > 0) {
                kotlin.math.abs(item.duration - expectedDurationSeconds)
            } else null

            val isLabel = isRecordLabel(matchArtist)
            val hasAlbumCtx = hasAlbumOrContextOverlap(wanted, got, expectedAlbum, item.albumTitle)

            val creditDisagreeOverride = !hasArtistMatch && (isExactCore || isNormalizedCore || isPhoneticCore) &&
                (isLabel || hasAlbumCtx) &&
                durationDifference != null && durationDifference <= 2

            if (!hasArtistMatch && !creditDisagreeOverride) {
                Log.d(TAG, "reject candidate id=${item.id}: artist mismatch target='$matchArtist' performer='${item.performerName}' for '$title'")
                return null
            }

            val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
            val candidateLanguages = extractLanguages("${item.title} ${item.albumTitle}")
            if (expectedLanguages.isNotEmpty() && candidateLanguages.isNotEmpty() &&
                expectedLanguages.intersect(candidateLanguages).isEmpty()
            ) {
                Log.d(TAG, "reject candidate id=${item.id} title='${item.title}' album='${item.albumTitle}': language mismatch expected=$expectedLanguages candidate=$candidateLanguages for '$title'")
                return null
            }

            val maxDurationDifference = when {
                hasArtistMatch && isExactCore -> 45
                hasArtistMatch -> 35
                else -> 2
            }
            if (durationDifference != null && durationDifference > maxDurationDifference) {
                Log.d(TAG, "reject candidate id=${item.id}: duration ${item.duration}s vs expected ${expectedDurationSeconds}s (Δ${durationDifference}s > ${maxDurationDifference}s) for '$title'")
                return null
            }

            var score = 1_000 - coreDistance * 30
            if (isExactCore) score += 400
            else if (isNormalizedCore) score += 300
            else if (isPhoneticCore) score += 200

            if (hasArtistMatch) {
                score += 300
            } else {
                score -= 300
            }

            if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
                score += 200
            }

            durationDifference?.let { score += (maxDurationDifference - it) * 5 }
            return score
        }

        private fun extractLanguages(raw: String): Set<String> {
            if (raw.isBlank()) return emptySet()
            return normalizeText(raw).split(' ').toSet().intersect(LANGUAGE_TOKENS)
        }

        private fun hasAlbumOrContextOverlap(
            wanted: TitleParts,
            got: TitleParts,
            expectedAlbum: String?,
            candidateAlbum: String?,
        ): Boolean {
            if (wanted.context.isNotEmpty() && got.context.isNotEmpty() && wanted.context.any { it in got.context }) {
                return true
            }
            if (!expectedAlbum.isNullOrBlank() && !candidateAlbum.isNullOrBlank()) {
                val normExp = normalizeText(expectedAlbum)
                val normCand = normalizeText(candidateAlbum)
                if (normExp.isNotBlank() && normCand.isNotBlank()) {
                    if (normExp == normCand || normExp.contains(normCand) || normCand.contains(normExp)) return true
                }
            }
            return false
        }

        private fun gateAmbiguousLanguage(
            verified: List<TidalCandidateItem>,
            title: String,
            expectedAlbum: String?,
        ): List<TidalCandidateItem> {
            if (verified.size < 2) return verified
            val expectedLanguages = extractLanguages("$title ${expectedAlbum.orEmpty()}")
            if (expectedLanguages.isNotEmpty()) {
                val matching = verified.filter {
                    val candidateLangs = extractLanguages("${it.title} ${it.albumTitle}")
                    candidateLangs.isEmpty() || candidateLangs.intersect(expectedLanguages).isNotEmpty()
                }
                if (matching.isNotEmpty()) return matching
            }
            return verified
        }

        private fun cleanForSearch(raw: String): String {
            return raw
                .replace(TOPIC_CHANNEL_SUFFIX, "")
                .replace(PIPE_NOISE, "")
                .replace(SOUNDTRACK_SUFFIX, "")
                .replace(FEATURING_CLAUSE, " ")
                .replace(ATMOS_VERSION_MARKER, " ")
                .replace(BRACKETED_DISPLAY_NOISE, " ")
                .replace(TRAILING_DISPLAY_NOISE, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    current[j] = minOf(
                        previous[j] + 1,
                        current[j - 1] + 1,
                        previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }

        private fun identityVariants(raw: String, artist: String): Set<String> {
            val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
                Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            val normalized = normalizeText(withoutArtistPrefix)
            return IDENTITY_VARIANT_PATTERNS.mapNotNullTo(linkedSetOf()) { (name, pattern) ->
                name.takeIf { pattern.containsMatchIn(normalized) }
            }
        }

        private fun isAtmosCandidate(item: TidalCandidateItem): Boolean =
            item.isAtmos || item.isSpatial ||
                identityVariants(item.title, item.performerName).contains("atmos")

        private fun foldTransliteration(str: String): String {
            return str
                .replace("aa", "a")
                .replace("ee", "i")
                .replace("oo", "u")
                .replace("th", "t")
                .replace("dh", "d")
                .replace("bh", "b")
                .replace("kh", "k")
                .replace("gh", "g")
                .replace("sh", "s")
                .replace("zh", "z")
                .replace("ph", "f")
                .replace("v", "w")
                .replace("ll", "l")
                .replace("tt", "t")
                .replace("dd", "d")
                .replace("pp", "p")
                .replace("mm", "m")
                .replace("nn", "n")
                .replace("ss", "s")
                .replace("rr", "r")
                .replace("cc", "c")
                .replace("yy", "y")
        }

        private fun cleanArtistIdentity(raw: String): String {
            val normalized = normalizeText(raw)
            val collapsed = normalized.replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
            return collapsed
                .split(' ')
                .filter { it !in ARTIST_NOISE_WORDS }
                .joinToString(" ")
                .trim()
        }

        internal fun artistNames(value: String): Set<List<String>> {
            val lowered = value.lowercase(Locale.ROOT).replace("$", "s")
            val collapsed = lowered
                .replace(Regex("""\b([a-z])\s*\.\s*(?=[a-z]\b)"""), "$1")
                .replace(Regex("""\b([a-z])\s+(?=[a-z]\b)"""), "$1")
            return collapsed
                .split(ARTIST_SEPARATORS)
                .map { name ->
                    name.split(Regex("""[\s.·/]+"""))
                        .map { it.replace(NON_ALPHANUMERIC, "") }
                        .filter { it.length > 1 && it !in ARTIST_NOISE_WORDS }
                }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        private fun runOfWords(outer: List<String>, inner: List<String>): Boolean {
            if (inner.isEmpty() || inner.size > outer.size) return false
            if (inner.size == 1 && outer.size > 1) return false
            return (0..outer.size - inner.size).any { at ->
                outer.subList(at, at + inner.size) == inner
            }
        }

        private fun isVerifiedArtistMatch(
            targetArtist: String,
            performer: String,
            albumArtist: String,
            performersText: String?,
        ): Boolean {
            val target = cleanArtistIdentity(targetArtist)
            if (target.isBlank()) return false
            val primaryIdentities = listOf(performer, albumArtist)
                .map(::cleanArtistIdentity)
                .filter(String::isNotBlank)
            if (primaryIdentities.any { it == target }) return true

            val targetArtists = artistNames(targetArtist)
            val candidateArtists = (listOf(performer, albumArtist) + listOfNotNull(performersText))
                .filter(String::isNotBlank)
                .flatMap { artistNames(it) }
                .toSet()

            if (targetArtists.isEmpty() || candidateArtists.isEmpty()) return false

            for (ta in targetArtists) {
                for (ca in candidateArtists) {
                    if (ta == ca) return true
                    if (runOfWords(ta, ca) || runOfWords(ca, ta)) return true
                }
            }

            val performingCredits = performersText.orEmpty()
                .split(Regex("""\s+-\s+"""))
                .map(::cleanArtistIdentity)
                .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
            val performingTokens = (primaryIdentities + performingCredits)
                .flatMap { it.split(' ') }
                .toSet()
            val targetTokens = target.split(' ').filter { it.length > 1 }.toSet()
            return targetTokens.isNotEmpty() && targetTokens.all(performingTokens::contains)
        }
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int? = null,
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        val addonUrlRaw = dataStore.get(LastWaveStreamingAddonUrlKey, DEFAULT_LASTWAVE_ADDON_URL).ifBlank { DEFAULT_LASTWAVE_ADDON_URL }
        val lastWaveEnabled = dataStore.get(LastWaveEnabledKey, true)
        if (!lastWaveEnabled || addonUrlRaw.isBlank()) return@withContext null

        val selectedQualityFormat = preferredQuality ?: dataStore.get(LastWaveStreamingQualityKey, QUALITY_MAX_HI_RES)
        if (selectedQualityFormat == QUALITY_YOUTUBE) return@withContext null

        val addonUrls = addonUrlRaw.split(Regex("""[\s,\n]+"""))
            .map { it.trim().removeSuffix("/") }
            .filter { it.isNotBlank() && (it.startsWith("http://") || it.startsWith("https://")) }

        if (addonUrls.isEmpty()) return@withContext null

        for (addonUrl in addonUrls) {
            Log.i(TAG, "resolveStream attempting for '$title' by '$artist' via addon ($addonUrl)")
            val stream = resolveFromAddon(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
                preferredQuality = selectedQualityFormat,
                addonBaseUrl = addonUrl,
            )
            if (stream != null) return@withContext stream
        }
        return@withContext null
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
        val parsedTarget = parseTitle(title, cleanArtist, expectedAlbum)
        val searchableTitle = (parsedTarget.words + parsedTarget.versions).joinToString(" ").ifBlank { cleanTitle }

        val artistList = cleanArtist.split(ARTIST_SEPARATORS)
            .map { cleanForSearch(it) }
            .filter { it.isNotBlank() }
        val primaryArtist = artistList.firstOrNull().orEmpty()
        val secondaryArtist = artistList.getOrNull(1).orEmpty()

        val queries = listOfNotNull(
            if (primaryArtist.isNotBlank()) "$searchableTitle $primaryArtist".trim() else null,
            if (secondaryArtist.isNotBlank()) "$searchableTitle $secondaryArtist".trim() else null,
            searchableTitle.trim(),
            if (cleanArtist.isNotBlank() && cleanArtist != primaryArtist) "$searchableTitle $cleanArtist".trim() else null,
            if (parsedTarget.core.length >= 4) foldTransliteration(searchableTitle).trim() else null,
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

            val verified = tracks.asSequence()
                .map { track ->
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
                }
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score ->
                        var finalScore = score
                        if (isAtmosPreferred && isAtmosCandidate(item)) finalScore += 200
                        item to finalScore
                    }
                }
                .sortedWith(compareByDescending { it.second })
                .map { it.first }
                .distinctBy { it.rawAddonId.ifBlank { it.id.toString() } }
                .toList()

            if (verified.isNotEmpty()) {
                val gated = gateAmbiguousLanguage(verified, title, expectedAlbum)
                if (gated.isNotEmpty()) {
                    candidates = gated
                    break
                }
            }
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "resolveFromAddon: No matching candidate found for '$title' by '$artist'")
            return null
        }

        val wantsHiRes = preferredQuality == QUALITY_MAX_HI_RES || preferredQuality == QUALITY_HI_RES_96

        val qualitiesToTry = when {
            isAtmosPreferred -> listOf("atmos", "hi_res", "lossless", "high")
            qualityParam == "hi_res" -> listOf("hi_res", "lossless", "high")
            qualityParam == "lossless" -> listOf("lossless", "hi_res", "high")
            qualityParam == "high" -> listOf("high", "lossless", "hi_res")
            qualityParam == "low" -> listOf("low", "high", "lossless", "hi_res")
            else -> listOf(qualityParam, "lossless", "high")
        }

        for (q in qualitiesToTry) {
            val wantAtmos = q == "atmos"
            val targetCandidates = if (wantAtmos) {
                val atmosMatches = candidates.filter { isAtmosCandidate(it) }
                if (atmosMatches.isNotEmpty()) atmosMatches else candidates
            } else {
                val stereoMatches = candidates.filter { !isAtmosCandidate(it) }
                if (stereoMatches.isNotEmpty()) stereoMatches else candidates
            }

            for (candidate in targetCandidates.take(4)) {
                currentCoroutineContext().ensureActive()
                val trackId = candidate.rawAddonId.ifBlank { candidate.id.toString() }
                val streamResult = addonClient.stream(trackId, q, wantAtmos)
                val stream = streamResult.getOrNull()

                val rawUrl = stream?.dataUrl?.takeIf { it.isNotBlank() }
                    ?: stream?.url?.takeIf { it.isNotBlank() }
                    ?: candidate.directStreamUrl?.takeIf { it.isNotBlank() }
                    ?: continue

                if (isDecoyStream(rawUrl)) continue

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
        }
        return null
    }
}
