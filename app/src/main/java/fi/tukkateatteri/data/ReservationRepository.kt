package fi.tukkateatteri.data

import kotlinx.coroutines.flow.Flow
import fi.tukkateatteri.data.spreadsheet.SheetFieldMapping

interface ReservationRepository {
    val performances: Flow<List<Performance>>
    val activePerformance: Flow<Performance?>
    val googleSheetSources: Flow<List<GoogleSheetSource>>
    val availableTicketTypes: Flow<List<TicketType>>
    val availablePaymentMethods: Flow<List<PaymentMethod>>

    fun reservationsForPerformance(performanceId: Long): Flow<List<Reservation>>
    suspend fun createPerformance(actName: String, date: String): Long
    suspend fun selectPerformance(performanceId: Long)
    suspend fun deletePerformance(performanceId: Long)
    suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>,
        accessToken: String? = null
    ): Long
    suspend fun updateReservation(reservation: Reservation, accessToken: String? = null)
    suspend fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String? = null)
    suspend fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    )
    suspend fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    )
    suspend fun deleteTicketSale(ticketSaleId: Long, accessToken: String? = null)
    suspend fun deleteReservation(reservationId: Long, accessToken: String? = null)
    suspend fun deleteAllReservations(accessToken: String? = null)
    suspend fun importGoogleSheet(spreadsheetUrl: String, accessToken: String): GoogleSheetImportResult
    suspend fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String
    ): Int
    suspend fun upsertGoogleSheetSource(source: GoogleSheetSource)
    suspend fun deleteGoogleSheetSource(actName: String)
    suspend fun saveSheetFieldMappings(
        spreadsheetUrl: String,
        accessToken: String,
        mappings: List<SheetFieldMapping>
    )
}

data class PendingPaymentAllocation(val method: PaymentMethod, val amountCents: Int)

data class GoogleSheetImportResult(
    val performanceCount: Int,
    val reservationCount: Int
)

class GoogleSheetSourceChangedException : IllegalStateException("Stored Sheet source metadata has changed.")

class NoGoogleSheetImportCandidatesException : IllegalStateException("Spreadsheet contains no importable performances.")

/** The change was saved on this device and will be retried after a Sheet sync. */
class GoogleSheetChangePendingException(cause: Throwable) :
    IllegalStateException("Change was saved locally but cloud synchronization failed.", cause)
