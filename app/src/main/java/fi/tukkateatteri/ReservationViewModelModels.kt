package fi.tukkateatteri

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException

sealed interface UiMessage {
    data class Text(
        @param:StringRes val messageResId: Int,
        val formatArgs: List<Any> = emptyList()
    ) : UiMessage

    data class Plural(
        @param:PluralsRes val messageResId: Int,
        val quantity: Int,
        val formatArgs: List<Any> = emptyList()
    ) : UiMessage
}

data class SheetMappingRequest(val headers: List<String>)

internal data class PendingMappingOperation(
    val spreadsheetUrl: String,
    val accessToken: String,
    @param:StringRes val failureMessageResId: Int,
    val retry: suspend () -> Unit
)

internal data class BackgroundSyncRequest(
    val spreadsheetUrl: String,
    val accessToken: String
)

internal fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

internal fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000L

internal class RefreshThrottle<K>(
    private val intervalMillis: Long,
    private val nowMillis: () -> Long = ::monotonicTimeMillis
) {
    private val previousAttempts = mutableMapOf<K, Long>()

    init {
        require(intervalMillis >= 0) { "Refresh interval must not be negative." }
    }

    fun tryAcquire(key: K): Boolean {
        val now = nowMillis()
        val previousAttempt = previousAttempts[key]
        if (previousAttempt != null && now - previousAttempt < intervalMillis) return false
        previousAttempts[key] = now
        return true
    }

    fun mark(key: K) {
        previousAttempts[key] = nowMillis()
    }
}
