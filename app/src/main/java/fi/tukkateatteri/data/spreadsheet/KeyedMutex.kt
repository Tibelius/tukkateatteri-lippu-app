package fi.tukkateatteri.data.spreadsheet

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Serializes work for the same key while allowing unrelated keys to proceed independently. */
internal class KeyedMutex {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withLock(key: String, action: suspend () -> T): T {
        val mutex = mutexes.computeIfAbsent(key) { Mutex() }
        mutex.lock()
        return try {
            action()
        } finally {
            mutex.unlock()
        }
    }
}
