package fi.tukkateatteri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleAuthorizationRequestsTest {
    @Test
    fun waitingActionsShareOneAuthorizationAndAllReceiveItsResult() {
        val requests = GoogleAuthorizationRequests()
        val received = mutableListOf<String>()
        val attempt = requireNotNull(requests.enqueue({ received += "first:$it" }, null))
        assertNull(requests.enqueue({ received += "second:$it" }, null))

        requests.complete(attempt, "token")
        requests.complete(attempt, "token")

        assertEquals(listOf("first:token", "second:token"), received)
    }

    @Test
    fun unavailableAuthorizationRunsEveryLocalFallbackOnce() {
        val requests = GoogleAuthorizationRequests()
        var fallbackCount = 0
        val attempt = requireNotNull(requests.enqueue({}, { fallbackCount++ }))
        requests.enqueue({}, { fallbackCount++ })
        requests.enqueue({}, null)

        requests.complete(attempt, null)
        requests.complete(attempt, null)

        assertEquals(2, fallbackCount)
    }

    @Test
    fun cancelledAttemptsCannotCompleteNewRequests() {
        val requests = GoogleAuthorizationRequests()
        var received: String? = null
        val oldAttempt = requireNotNull(requests.enqueue({}, null))
        requests.cancel()
        val newAttempt = requireNotNull(requests.enqueue({ received = it }, null))

        requests.complete(oldAttempt, "stale-token")
        assertNull(received)
        requests.complete(newAttempt, "current-token")

        assertNotEquals(oldAttempt, newAttempt)
        assertEquals("current-token", received)
    }

    @Test
    fun actionsMayRequestAnotherAuthorizationDuringCompletion() {
        val requests = GoogleAuthorizationRequests()
        var followUpAttempt: Long? = null
        var followUpToken: String? = null
        val attempt = requireNotNull(requests.enqueue({
            followUpAttempt = requests.enqueue({ followUpToken = it }, null)
        }, null))

        requests.complete(attempt, "first-token")
        requests.complete(requireNotNull(followUpAttempt), "second-token")

        assertEquals("second-token", followUpToken)
    }
}
