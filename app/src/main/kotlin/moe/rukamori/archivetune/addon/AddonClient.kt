/*
 * ArchiveTune (2026)
 * Ported from LastWave-Native under GPL-3.0
 */

package moe.rukamori.archivetune.addon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class AddonClient(
    rawBaseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
) {
    val baseUrl: String = rawBaseUrl.trim().trimEnd('/')

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun manifest(): Result<AddonManifest> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty addon URL"))
        val url = if (baseUrl.endsWith("/manifest.json")) baseUrl else "$baseUrl/manifest.json"
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string().orEmpty()
                Result.success(json.decodeFromString<AddonManifest>(body))
            }
        } catch (e: Exception) {
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
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string().orEmpty()
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
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext Result.failure(Exception("HTTP ${res.code}"))
                val body = res.body?.string().orEmpty()
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
            Result.failure(e)
        }
    }
}
