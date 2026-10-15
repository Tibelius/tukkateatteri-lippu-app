package fi.tukkateatteri

import fi.tukkateatteri.data.GoogleSheetChangePendingException
import fi.tukkateatteri.data.GoogleSheetSourceChangedException
import fi.tukkateatteri.data.GoogleSheetSynchronizationConflictException
import fi.tukkateatteri.data.spreadsheet.GoogleSheetLockFailure
import fi.tukkateatteri.data.spreadsheet.GoogleSheetLockedException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SheetSyncFailureTest {
    @Test
    fun offlineAndUnexpectedFailuresOfferAnUploadRetry() {
        assertEquals(SheetSyncFailureReason.UPLOAD_FAILED, IOException("offline").toSheetSyncFailureReason())
        assertEquals(SheetSyncFailureReason.UPLOAD_FAILED, IllegalStateException().toSheetSyncFailureReason())
    }

    @Test
    fun locksAndConflictsHaveSpecificMessages() {
        val locked = GoogleSheetLockedException(GoogleSheetLockFailure.HELD_BY_ANOTHER_DEVICE, "24.10")

        assertEquals(SheetSyncFailureReason.LOCKED, locked.toSheetSyncFailureReason())
        assertEquals(
            SheetSyncFailureReason.CONFLICT,
            GoogleSheetSynchronizationConflictException(1).toSheetSyncFailureReason()
        )
        assertEquals(SheetSyncFailureReason.SOURCE_CHANGED, GoogleSheetSourceChangedException().toSheetSyncFailureReason())
    }

    @Test
    fun failedMutationRetainsItsActualPerformanceAndUnderlyingFailure() {
        val failure = GoogleSheetChangePendingException(
            GoogleSheetSynchronizationConflictException(1),
            performanceId = 42
        )

        assertEquals(42L, failure.performanceId)
        assertEquals(SheetSyncFailureReason.CONFLICT, failure.toSheetSyncFailureReason())
    }
}
