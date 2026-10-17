package moe.rukamori.archivetune.lossless

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class LosslessStreamResult(
    val url: String,
    val mimeType: String = "audio/flac",
    val bitDepth: Int = 24,
    val sampleRate: Int = 192000,
    val isLossless: Boolean = true,
)

@Singleton
class LosslessAudioProvider @Inject constructor(
    private val http: OkHttpClient,
) {
    suspend fun resolveLosslessStream(title: String, artist: String): LosslessStreamResult? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        runCatching {
            val query = java.net.URLEncoder.encode("$artist $title", "UTF-8")
            val request = Request.Builder()
                .url("https://api.lossless.example/v1/stream?q=$query")
                .header("User-Agent", "LastArchiveWaveTune/15.0.0")
                .build()
            http.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        return@withContext LosslessStreamResult(url = body.trim())
                    }
                }
            }
            null
        }.getOrNull()
    }
}
