package fi.tukkateatteri.data

import androidx.room.withTransaction
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.GoogleSheetSourceDao
import fi.tukkateatteri.data.local.ReservationDao
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.TicketSaleEntity
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.local.toEntity
import fi.tukkateatteri.data.local.toGoogleSheetSource
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import fi.tukkateatteri.data.spreadsheet.GoogleSheetImportCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ReservationRepository {
    val reservations: Flow<List<Reservation>>
    val googleSheetSources: Flow<List<GoogleSheetSource>>

    suspend fun addAdmission(lastName: String, firstName: String, contact: String, seatCount: Int, admissionType: AdmissionType): Long
    suspend fun updateReservation(reservation: Reservation)
    suspend fun addTicketSale(reservationId: Long, ticketType: TicketType, quantity: Int, payments: List<PendingPaymentAllocation>)
    suspend fun deleteTicketSale(ticketSaleId: Long)
    suspend fun deleteReservation(reservationId: Long)
    suspend fun deleteAllReservations()
    suspend fun exportSpreadsheetRows(): List<ReservationSpreadsheetRow>
    suspend fun importSpreadsheetRows(rows: List<ReservationSpreadsheetRow>)
    suspend fun loadGoogleSheetImportCandidates(spreadsheetUrl: String, accessToken: String): List<GoogleSheetImportCandidate>
    suspend fun importGoogleSheet(spreadsheetUrl: String, accessToken: String, sheetTitle: String): Int
    suspend fun exportGoogleSheet(spreadsheetUrl: String, sheetTitle: String, accessToken: String)
    suspend fun upsertGoogleSheetSource(source: GoogleSheetSource)
    suspend fun deleteGoogleSheetSource(actName: String)
}

data class PendingPaymentAllocation(val method: PaymentMethod, val amountCents: Int)

class RoomReservationRepository(
    private val database: ReservationDatabase,
    private val reservationDao: ReservationDao,
    private val googleSheetSourceDao: GoogleSheetSourceDao,
    private val googleSheetsClient: GoogleSheetsClient = GoogleSheetsClient()
) : ReservationRepository {
    override val reservations: Flow<List<Reservation>> = reservationDao.observeAllWithTicketSales()
        .map { reservations -> reservations.map { reservation -> reservation.toReservation() } }
    override val googleSheetSources: Flow<List<GoogleSheetSource>> = googleSheetSourceDao.observeAll()
        .map { sources -> sources.map { source -> source.toGoogleSheetSource() } }

    override suspend fun addAdmission(lastName: String, firstName: String, contact: String, seatCount: Int, admissionType: AdmissionType): Long =
        reservationDao.insert(ReservationEntity(lastName = lastName.trim(), firstName = firstName.trim(), contact = contact.trim(), seatCount = seatCount, admissionType = admissionType))

    override suspend fun updateReservation(reservation: Reservation) {
        val existingReservation = reservationDao.getById(reservation.id)
        reservationDao.update(ReservationEntity(id = reservation.id, lastName = reservation.lastName.trim(), firstName = reservation.firstName.trim(), contact = reservation.contact.trim(), seatCount = reservation.seatCount, notes = reservation.notes.trim(), sourceIdentity = existingReservation?.sourceIdentity.orEmpty(), admissionType = reservation.admissionType, isPresent = reservation.isPresent, paymentMethod = null))
    }

    override suspend fun addTicketSale(reservationId: Long, ticketType: TicketType, quantity: Int, payments: List<PendingPaymentAllocation>) {
        require(quantity > 0) { "Ticket quantity must be positive." }
        require(payments.all { it.amountCents >= 0 }) { "Payment amounts must not be negative." }
        database.withTransaction {
            val ticketSaleId = reservationDao.insertTicketSale(TicketSaleEntity(reservationId = reservationId, ticketType = ticketType, quantity = quantity, unitPriceCents = ticketType.defaultPriceCents))
            reservationDao.insertPaymentAllocations(payments.map { payment -> PaymentAllocationEntity(ticketSaleId = ticketSaleId, paymentMethod = payment.method, amountCents = payment.amountCents) })
        }
    }

    override suspend fun deleteTicketSale(ticketSaleId: Long) = reservationDao.deleteTicketSaleById(ticketSaleId)
    override suspend fun deleteReservation(reservationId: Long) = reservationDao.deleteById(reservationId)
    override suspend fun deleteAllReservations() = reservationDao.deleteAll()

    override suspend fun exportSpreadsheetRows(): List<ReservationSpreadsheetRow> = reservationDao.getAllWithTicketSales().map { reservation -> ReservationSpreadsheetRow.fromReservation(reservation.toReservation()) }

    override suspend fun importSpreadsheetRows(rows: List<ReservationSpreadsheetRow>) {
        database.withTransaction {
            rows.forEach { row ->
                val existingReservation = if (row.sourceIdentity.isNotBlank()) {
                    reservationDao.findBySourceIdentity(row.sourceIdentity)
                } else {
                    null
                }
                val reservationId = existingReservation?.id ?: reservationDao.insert(
                    ReservationEntity(
                        lastName = row.lastName.trim(),
                        firstName = row.firstName.trim(),
                        contact = row.contact.trim(),
                        seatCount = row.reservedSeatCount,
                        notes = row.notes,
                        sourceIdentity = row.sourceIdentity,
                        admissionType = AdmissionType.RESERVATION
                    )
                )
                if (existingReservation != null) {
                    reservationDao.update(
                        existingReservation.copy(
                            lastName = row.lastName.trim(),
                            firstName = row.firstName.trim(),
                            contact = row.contact.trim(),
                            seatCount = row.reservedSeatCount,
                            notes = row.notes
                        )
                    )
                    reservationDao.deleteTicketSalesForReservation(reservationId)
                }
                row.ticketCounts.forEach { (ticketType, quantity) ->
                    if (quantity > 0) addTicketSale(reservationId, ticketType, quantity, emptyList())
                }
            }
        }
    }

    override suspend fun loadGoogleSheetImportCandidates(spreadsheetUrl: String, accessToken: String): List<GoogleSheetImportCandidate> =
        googleSheetsClient.loadImportCandidates(spreadsheetUrl, accessToken)

    override suspend fun importGoogleSheet(spreadsheetUrl: String, accessToken: String, sheetTitle: String): Int {
        val rows = googleSheetsClient.importTab(spreadsheetUrl, accessToken, sheetTitle)
        importSpreadsheetRows(rows)
        return rows.size
    }

    override suspend fun exportGoogleSheet(spreadsheetUrl: String, sheetTitle: String, accessToken: String) {
        googleSheetsClient.exportRows(
            spreadsheetUrl = spreadsheetUrl,
            sheetTitle = sheetTitle,
            accessToken = accessToken,
            rows = exportSpreadsheetRows()
        )
    }

    override suspend fun upsertGoogleSheetSource(source: GoogleSheetSource) {
        googleSheetSourceDao.upsert(source.toEntity())
    }

    override suspend fun deleteGoogleSheetSource(actName: String) {
        googleSheetSourceDao.deleteByActName(actName)
    }
}
