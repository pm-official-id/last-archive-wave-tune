/*
 * ArchiveTune (2026)
 * Source Debug Logger for tracking audio stream resolution responses & errors across all sources.
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SourceDebugLogger {
    private const val TAG = "SourceDebugLogger"
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        runCatching {
            val dir = context.externalCacheDir ?: context.filesDir
            logFile = File(dir, "source_debug.log")
            if (logFile?.exists() == false) {
                logFile?.createNewFile()
            }
        }
    }

    @Synchronized
    fun log(source: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] [$source] $message${throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""}\n"
        
        Log.i(TAG, "[$source] $message")
        
        runCatching {
            logFile?.appendText(entry)
        }
    }

    fun readLogs(context: Context): String {
        init(context)
        return runCatching {
            logFile?.readText().orEmpty().ifBlank { "No source logs recorded yet." }
        }.getOrDefault("Error reading log file.")
    }

    fun clearLogs(context: Context) {
        init(context)
        runCatching {
            logFile?.writeText("")
        }
    }

    fun getLogFilePath(context: Context): String {
        init(context)
        return logFile?.absolutePath ?: "N/A"
    }
}
