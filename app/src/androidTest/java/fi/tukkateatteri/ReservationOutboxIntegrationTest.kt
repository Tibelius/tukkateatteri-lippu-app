package fi.tukkateatteri

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.ReservationSyncState
import fi.tukkateatteri.data.RoomReservationRepository
import fi.tukkateatteri.data.markPendingChangeSynced
import fi.tukkateatteri.data.runCloudMutation
import fi.tukkateatteri.data.stagePendingChanges
import fi.tukkateatteri.data.local.PendingSheetChangeEntity
import fi.tukkateatteri.data.local.PendingSheetOperation
import fi.tukkateatteri.data.local.PerformanceEntity
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRowSnapshot
import fi.tukkateatteri.data.spreadsheet.toSnapshotJson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReservationOutboxIntegrationTest {
    private lateinit var database: ReservationDatabase
    private lateinit var repository: RoomReservationRepository
    private var performanceId = 0L
    private var reservationId = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ReservationDatabase::class.java
        ).addCallback(ReservationDatabase.CONSTRAINTS_CALLBACK).build()
        repository = RoomReservationRepository(
            database, database.reservationDao(), database.performanceDao(),
            database.googleSheetSourceDao(), database.pendingSheetChangeDao(),
            database.sheetFieldDefinitionDao(), database.sheetFieldAliasDao()
        )
        performanceId = database.performanceDao().insert(
            PerformanceEntity(
                actName = "Testi", date = "24.10.2026", sourceSheetTitle = "24.10", isActive = true
            )
        )
        repository.upsertGoogleSheetSource(
            GoogleSheetSource("Testi", "https://docs.google.com/spreadsheets/d/test-id/edit")
        )
        reservationId = database.reservationDao().insert(
            ReservationEntity(
                performanceId = performanceId,
                lastName = "Virtanen", firstName = "Maija", contact = "", seatCount = 1
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun localWriteRollsBackIfOutboxStagingFails() = runBlocking {
        try {
            repository.runCloudMutation(
                operation = "test atomic staging",
                accessToken = null,
                mutation = {
                    val reservation = requireNotNull(database.reservationDao().getById(reservationId))
                    database.reservationDao().update(reservation.copy(notes = "Changed"))
                },
                affectedReservationIds = { error("Staging failed") }
            )
            org.junit.Assert.fail("Expected staging failure")
        } catch (_: IllegalStateException) {
            // The mutation and outbox staging must be one transaction.
        }

        assertEquals("", database.reservationDao().getById(reservationId)?.notes)
        assertTrue(database.pendingSheetChangeDao().getAllByPerformanceId(performanceId).isEmpty())
    }

    @Test
    fun restagingChangesRevisionButKeepsOriginalSynchronizationBase() = runBlocking {
        repository.stagePendingChanges(performanceId, listOf(reservationId), emptyMap())
        val first = requireNotNull(database.pendingSheetChangeDao().findByReservationId(reservationId))
        repository.stagePendingChanges(performanceId, listOf(reservationId), emptyMap())
        val second = requireNotNull(database.pendingSheetChangeDao().findByReservationId(reservationId))

        assertNotEquals(first.id, second.id)
        assertEquals(first.baseRowJson, second.baseRowJson)
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun uploadAcknowledgesOnlyTheRevisionThatWasSent() = runBlocking {
        val originalRow = row(notes = "Uploaded")
        val uploaded = change("uploaded", originalRow)
        database.pendingSheetChangeDao().upsert(uploaded)
        val newer = change("newer", row(notes = "Newer edit"))
        database.pendingSheetChangeDao().upsert(newer)
        val entity = requireNotNull(database.reservationDao().getById(reservationId))
        database.reservationDao().update(entity.copy(notes = "Newer edit", syncState = ReservationSyncState.PENDING))

        repository.markPendingChangeSynced(uploaded, "sheet-row")

        val pending = requireNotNull(database.pendingSheetChangeDao().findByReservationId(reservationId))
        assertEquals("newer", pending.id)
        assertEquals("Newer edit", pending.desiredRowJson?.toReservationSpreadsheetRowSnapshot()?.notes)
        assertEquals("Uploaded", pending.baseRowJson?.toReservationSpreadsheetRowSnapshot()?.notes)
        assertEquals(ReservationSyncState.PENDING, database.reservationDao().getById(reservationId)?.syncState)
        assertEquals("Newer edit", database.reservationDao().getById(reservationId)?.notes)
    }

    @Test
    fun matchingUploadClearsItsOutboxEntry() = runBlocking {
        val uploaded = change("uploaded", row("Saved"))
        database.pendingSheetChangeDao().upsert(uploaded)

        repository.markPendingChangeSynced(uploaded, "sheet-row")

        assertEquals(null, database.pendingSheetChangeDao().findByReservationId(reservationId))
        assertEquals(ReservationSyncState.SYNCED, database.reservationDao().getById(reservationId)?.syncState)
        assertEquals("sheet-row", database.reservationDao().getById(reservationId)?.sheetRowId)
    }

    private fun change(id: String, row: ReservationSpreadsheetRow) = PendingSheetChangeEntity(
        id = id, reservationId = reservationId, performanceId = performanceId,
        operation = PendingSheetOperation.UPSERT, baseRowJson = null,
        desiredRowJson = row.toSnapshotJson(), createdAt = 1
    )

    private fun row(notes: String) = ReservationSpreadsheetRow(
        lastName = "Virtanen", firstName = "Maija", contact = "",
        reservedSeatCount = 1, arrivalCount = 0, reservedTicketCounts = emptyMap(),
        paymentAmountsCents = emptyMap(), notes = notes
    )
}
