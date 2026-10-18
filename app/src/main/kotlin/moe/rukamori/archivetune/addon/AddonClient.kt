/*
 * ArchiveTune (2026)
 * Ported from LastWave-Native under GPL-3.0
 */

package moe.rukamori.archivetune.addon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import moe.rukamori.archivetune.utils.SourceDebugLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AddonClient(
    rawBaseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val clientSecret: String? = null,
) {
    val baseUrl: String = normalizeBase(rawBaseUrl)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun signRequest(urlStr: String, method: String, reqBuilder: Request.Builder) {
        val secret = clientSecret?.takeIf { it.isNotBlank() } ?: DEFAULT_ADDON_SECRET
        try {
            val uri = URI(urlStr)
            val path = uri.path ?: "/"
            val tokenMatch = Regex("""/a/([^/]+)""").find(path)
            val token = tokenMatch?.groupValues?.get(1) ?: ""
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val message = "$ts\n${method.uppercase()}\n$path\n$token"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val sign = mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            reqBuilder.header("X-LW-TS", ts)
            reqBuilder.header("X-LW-Sign", sign)
        } catch (e: Exception) {
            Log.w("AddonClient", "Failed to sign addon request: ${e.message}")
        }
    }

    suspend fun manifest(): Result<AddonManifest> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val url = if (baseUrl.endsWith("/manifest.json")) baseUrl else "$baseUrl/manifest.json"
        try {
            SourceDebugLogger.log("LastWave-Addon", "GET manifest URL=$url")
            val reqBuilder = Request.Builder().url(url).get()
                .header("User-Agent", "LastWave-Player/1.0")
                .header("X-LW-Intent", "stream")
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                SourceDebugLogger.log("LastWave-Addon", "Manifest response HTTP ${res.code} (bytes=${body.length}): ${body.take(200)}")
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                Result.success(json.decodeFromString<AddonManifest>(body))
            }
        } catch (e: Exception) {
            SourceDebugLogger.log("LastWave-Addon", "Manifest exception for $url", e)
            Result.failure(e)
        }
    }

    suspend fun search(query: String, quality: String = "lossless", atmos: Boolean = false): Result<List<AddonTrack>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val q = query.trim()
        if (q.isBlank()) return@withContext Result.success(emptyList())

        val encodedQ = URLEncoder.encode(q, "UTF-8")
        val atmosParam = if (atmos) "auto" else "none"
        val url = "$baseUrl/search?q=$encodedQ&quality=$quality&atmos=$atmosParam"

        try {
            SourceDebugLogger.log("LastWave-Addon", "GET search URL=$url")
            val reqBuilder = Request.Builder().url(url).get()
                .header("User-Agent", "LastWave-Player/1.0")
                .header("X-LW-Intent", "stream")
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                SourceDebugLogger.log("LastWave-Addon", "Search response HTTP ${res.code} (bytes=${body.length}): ${body.take(300)}")
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val tracks = when (root) {
                    is JsonArray -> runCatching { json.decodeFromJsonElement<List<AddonTrack>>(root) }.getOrNull()
                    is JsonObject -> {
                        val array = root["tracks"] ?: root["data"] ?: root["results"] ?: root["items"]
                        if (array is JsonArray) runCatching { json.decodeFromJsonElement<List<AddonTrack>>(array) }.getOrNull() else null
                    }
                    else -> null
                } ?: runCatching { json.decodeFromString<AddonSearchResponse>(body).tracks }.getOrNull() ?: emptyList()
                Result.success(tracks)
            }
        } catch (e: Exception) {
            SourceDebugLogger.log("LastWave-Addon", "Search exception for '$query'", e)
            Result.failure(e)
        }
    }

    suspend fun stream(
        trackId: String,
        quality: String = "lossless",
        atmos: Boolean = false,
    ): Result<AddonStream> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val cleanId = trackId.trim()
        if (cleanId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty track id"))

        val atmosParam = if (atmos) "auto" else "none"
        val url = "$baseUrl/stream/$cleanId?quality=$quality&atmos=$atmosParam"

        try {
            SourceDebugLogger.log("LastWave-Addon", "GET stream URL=$url")
            val reqBuilder = Request.Builder().url(url).get()
                .header("User-Agent", "LastWave-Player/1.0")
                .header("X-LW-Intent", "stream")
            signRequest(url, "GET", reqBuilder)
            val req = reqBuilder.build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                SourceDebugLogger.log("LastWave-Addon", "Stream response HTTP ${res.code} (bytes=${body.length}): ${body.take(300)}")
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                val streamElement = (root as? JsonObject)?.let { obj ->
                    obj["stream"] ?: obj["data"] ?: obj
                } ?: root
                val parsed = if (streamElement != null && streamElement != root) {
                    runCatching { json.decodeFromJsonElement<AddonStream>(streamElement) }
                        .getOrElse { json.decodeFromString<AddonStream>(body) }
                } else {
                    json.decodeFromString<AddonStream>(body)
                }
                Result.success(parsed)
            }
        } catch (e: Exception) {
            SourceDebugLogger.log("LastWave-Addon", "Stream exception for '$trackId'", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val DEFAULT_ADDON_SECRET = "36d96a751b12ee481c281a8a8e64c482d0c1634a22061ab72f40175017818b85"

        fun normalizeBase(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            return if (trimmed.endsWith("/manifest.json", ignoreCase = true)) {
                trimmed.substringBeforeLast("/manifest.json").trimEnd('/')
            } else {
                trimmed
            }
        }
    }
}
