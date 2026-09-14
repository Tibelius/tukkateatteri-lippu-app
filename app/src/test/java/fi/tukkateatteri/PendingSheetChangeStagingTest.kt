package fi.tukkateatteri

import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.local.PendingSheetChangeEntity
import fi.tukkateatteri.data.local.PendingSheetChangeStatus
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.pendingChangeBaseRowJson
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingSheetChangeStagingTest {
    private val localDoorSaleRow = ReservationSpreadsheetRow.fromReservation(
        Reservation(
            id = 42,
            performanceId = 1,
            lastName = "- Ovimyynti",
            firstName = "",
            contact = "",
            seatCount = 1,
            admissionType = AdmissionType.DOOR_SALE
        )
    )

    @Test
    fun subsequentEditPreservesNullBaseForReservationNotYetUploaded() {
        val pendingCreation = pendingChange(baseRowJson = null)

        assertNull(
            pendingChangeBaseRowJson(
                existingChange = pendingCreation,
                capturedBaseRow = localDoorSaleRow
            )
        )
    }

    @Test
    fun subsequentEditPreservesOriginalBaseForExistingSheetRow() {
        val originalBase = localDoorSaleRow.copy(lastName = "Original").toSnapshotJson()

        assertEquals(
            originalBase,
            pendingChangeBaseRowJson(
                existingChange = pendingChange(baseRowJson = originalBase),
                capturedBaseRow = localDoorSaleRow.copy(lastName = "Later local state")
            )
        )
    }

    private fun pendingChange(baseRowJson: String?) = PendingSheetChangeEntity(
        id = "change-id",
        reservationId = 42,
        performanceId = 1,
        operation = PendingSheetOperation.UPSERT,
        baseRowJson = baseRowJson,
        desiredRowJson = localDoorSaleRow.toSnapshotJson(),
        status = PendingSheetChangeStatus.PENDING,
        createdAt = 1L
    )
}
