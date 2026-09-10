package fi.tukkateatteri

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshThrottleTest {
    @Test
    fun tryAcquire_throttlesEachKeyIndependently() {
        var now = 1_000L
        val throttle = RefreshThrottle<String>(intervalMillis = 120_000L) { now }

        assertTrue(throttle.tryAcquire("24.10"))
        assertFalse(throttle.tryAcquire("24.10"))
        assertTrue(throttle.tryAcquire("27.10"))

        now += 120_000L

        assertTrue(throttle.tryAcquire("24.10"))
    }

    @Test
    fun mark_startsCooldownAfterAnotherRefreshPath() {
        var now = 5_000L
        val throttle = RefreshThrottle<String>(intervalMillis = 60_000L) { now }

        throttle.mark("24.10")
        now += 30_000L

        assertFalse(throttle.tryAcquire("24.10"))
    }
}
