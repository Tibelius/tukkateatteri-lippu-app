package fi.tukkateatteri.logging

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Central application logger. INFO and above are emitted by default. DEBUG and VERBOSE can be
 * enabled at runtime with `adb shell setprop log.tag.Tukkateatteri DEBUG`.
 */
internal object AppLog {
    private const val TAG = "Tukkateatteri"

    fun verbose(component: String, message: () -> String) {
        log(Log.VERBOSE, component, message)
    }

    fun debug(component: String, message: () -> String) {
        log(Log.DEBUG, component, message)
    }

    fun info(component: String, message: () -> String) {
        log(Log.INFO, component, message)
    }

    fun warning(component: String, throwable: Throwable? = null, message: () -> String) {
        log(Log.WARN, component, message, throwable)
    }

    fun error(component: String, throwable: Throwable, message: () -> String) {
        log(Log.ERROR, component, message, throwable)
    }

    fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MILLISECOND

    private fun log(
        priority: Int,
        component: String,
        message: () -> String,
        throwable: Throwable? = null
    ) {
        if (priority < Log.INFO && !Log.isLoggable(TAG, priority)) return
        val renderedMessage = buildString {
            append('[')
            append(component)
            append("] ")
            append(message())
            throwable?.let {
                append(" Reason: ")
                append(it.toSanitizedLogReason())
            }
        }
        if (throwable == null) {
            Log.println(priority, TAG, renderedMessage)
        } else {
            val stackTrace = Log.getStackTraceString(throwable).sanitizeSensitiveLogText()
            Log.println(priority, TAG, "$renderedMessage\n$stackTrace")
        }
    }

    private const val NANOS_PER_MILLISECOND = 1_000_000L
}

internal fun Throwable.toSanitizedLogReason(): String {
    if (this is CancellationException) return "operation was cancelled"
    val detail = message
        ?.sanitizeSensitiveLogText()
        ?.take(MAX_REASON_LENGTH)
        ?.takeIf(String::isNotBlank)
    return if (detail == null) javaClass.simpleName else "${javaClass.simpleName}: $detail"
}

internal fun String.sanitizeSensitiveLogText(): String =
    replace(BEARER_TOKEN_PATTERN, "Bearer [redacted]")
        .replace(ACCESS_TOKEN_PARAMETER_PATTERN, "access_token=[redacted]")
        .replace(SPREADSHEET_DOCUMENT_URL_PATTERN, "https://docs.google.com/spreadsheets/d/[redacted]")
        .replace(SPREADSHEET_API_URL_PATTERN, "https://sheets.googleapis.com/v4/spreadsheets/[redacted]")

private const val MAX_REASON_LENGTH = 300
private val BEARER_TOKEN_PATTERN = Regex("Bearer\\s+[^\\s]+", RegexOption.IGNORE_CASE)
private val ACCESS_TOKEN_PARAMETER_PATTERN = Regex("access_token=[^&\\s]+", RegexOption.IGNORE_CASE)
private val SPREADSHEET_DOCUMENT_URL_PATTERN = Regex(
    "https://docs\\.google\\.com/spreadsheets/d/[^/\\s?#]+[^\\s]*",
    RegexOption.IGNORE_CASE
)
private val SPREADSHEET_API_URL_PATTERN = Regex(
    "https://sheets\\.googleapis\\.com/v4/spreadsheets/[^/\\s?:]+",
    RegexOption.IGNORE_CASE
)
