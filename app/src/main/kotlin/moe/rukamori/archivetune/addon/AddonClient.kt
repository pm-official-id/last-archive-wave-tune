/*
 * ArchiveTune (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.addon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AddonClient(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val signingSecret = "36d96a751b12ee481c281a8a8e64c482d0c1634a22061ab72f40175017818b85"

    private fun cleanBaseUrl(url: String): String =
        url.trim().removeSuffix("/")

    private fun signRequest(builder: Request.Builder, path: String) {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val dataToSign = "$path:$timestamp"
        val signature = runCatching {
            val hmac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(signingSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            hmac.init(secretKey)
            val bytes = hmac.doFinal(dataToSign.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrDefault("")

        builder
            .header("User-Agent", "LastWave-Player/1.0")
            .header("X-LW-Intent", "stream")
            .header("X-LW-TS", timestamp)
            .header("X-LW-Sign", signature)
    }

    suspend fun getManifest(): Result<AddonManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "${cleanBaseUrl(baseUrl)}/manifest.json"
            val builder = Request.Builder().url(url).get()
            signRequest(builder, "/manifest.json")

            okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Addon manifest HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty manifest response")
                json.decodeFromString<AddonManifest>(body)
            }
        }
    }

    suspend fun search(
        query: String,
        quality: String = "hi_res",
        atmos: Boolean = false,
    ): Result<List<AddonTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val atmosParam = if (atmos) "auto" else "none"
            val path = "/search?q=$encodedQuery&quality=$quality&atmos=$atmosParam"
            val url = "${cleanBaseUrl(baseUrl)}$path"

            val builder = Request.Builder().url(url).get()
            signRequest(builder, path)

            okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Addon search HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty search response")
                json.decodeFromString<AddonSearchResponse>(body).tracks
            }
        }
    }

    suspend fun stream(
        trackId: String,
        quality: String = "hi_res",
        atmos: Boolean = false,
        isDownload: Boolean = false,
    ): Result<AddonStream> = withContext(Dispatchers.IO) {
        runCatching {
            val atmosParam = if (atmos) "auto" else "none"
            val downloadParam = if (isDownload) "&intent=download" else ""
            val path = "/stream/$trackId?quality=$quality&atmos=$atmosParam$downloadParam"
            val url = "${cleanBaseUrl(baseUrl)}$path"

            val builder = Request.Builder().url(url).get()
            signRequest(builder, path)

            okHttpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Addon stream HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty stream response")
                json.decodeFromString<AddonStream>(body)
            }
        }
    }
}
