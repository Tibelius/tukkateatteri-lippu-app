package fi.tukkateatteri.data.spreadsheet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyedMutexTest {
    @Test
    fun operationsUsingTheSameKeyAreSerialized() = runBlocking {
        val keyedMutex = KeyedMutex()
        var activeOperations = 0
        var peakActiveOperations = 0

        coroutineScope {
            List(OPERATION_COUNT) {
                async(Dispatchers.Default) {
                    keyedMutex.withLock("same-performance") {
                        activeOperations += 1
                        peakActiveOperations = maxOf(peakActiveOperations, activeOperations)
                        delay(OPERATION_DURATION_MILLIS)
                        activeOperations -= 1
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, peakActiveOperations)
    }

    @Test
    fun operationsUsingDifferentKeysCanProceedIndependently() = runBlocking {
        val keyedMutex = KeyedMutex()
        val firstLockAcquired = CompletableDeferred<Unit>()
        val releaseFirstLock = CompletableDeferred<Unit>()
        val firstOperation = launch {
            keyedMutex.withLock("first-performance") {
                firstLockAcquired.complete(Unit)
                releaseFirstLock.await()
            }
        }

        firstLockAcquired.await()
        try {
            withTimeout(INDEPENDENT_OPERATION_TIMEOUT_MILLIS) {
                keyedMutex.withLock("second-performance") { }
            }
        } finally {
            releaseFirstLock.complete(Unit)
            firstOperation.join()
        }
    }

    private companion object {
        const val OPERATION_COUNT = 8
        const val OPERATION_DURATION_MILLIS = 10L
        const val INDEPENDENT_OPERATION_TIMEOUT_MILLIS = 1_000L
    }
}
