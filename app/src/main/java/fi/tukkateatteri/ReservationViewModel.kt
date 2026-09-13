package fi.tukkateatteri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.SheetFieldClassification
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toLogSummary
import fi.tukkateatteri.logging.toPaymentLogSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

class ReservationViewModel(
    private val reservationRepository: ReservationRepository
) : ViewModel() {
    private val addedReservationIdsChannel = Channel<Long>(Channel.BUFFERED)
    private val statisticsState = StatisticsState(reservationRepository, viewModelScope)
    private val sheetSync = SheetSyncController(reservationRepository, viewModelScope)

    val performances: StateFlow<List<Performance>> = reservationRepository.performances.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )
    val activePerformance: StateFlow<Performance?> = reservationRepository.activePerformance.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = null
    )
    @OptIn(ExperimentalCoroutinesApi::class)
    val reservations: StateFlow<List<Reservation>> = activePerformance
        .flatMapLatest { performance ->
            if (performance == null) {
                flowOf(emptyList())
            } else {
                reservationRepository.reservationsForPerformance(performance.id)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList()
        )
    val googleSheetSources: StateFlow<List<GoogleSheetSource>> = reservationRepository.googleSheetSources.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )
    val availableTicketTypes: StateFlow<List<TicketType>> = reservationRepository.availableTicketTypes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TicketType.entries.filterNot { it == TicketType.UNSPECIFIED }
    )
    val availablePaymentMethods: StateFlow<List<PaymentMethod>> = reservationRepository.availablePaymentMethods.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = PaymentMethod.entries
    )
    val addedReservationIds = addedReservationIdsChannel.receiveAsFlow()
    val transferMessage: StateFlow<UiMessage?> = sheetSync.transferMessage
    val isTransferInProgress: StateFlow<Boolean> = sheetSync.isTransferInProgress
    val sheetMappingRequest: StateFlow<SheetMappingRequest?> = sheetSync.mappingRequest
    val syncingPerformanceIds: StateFlow<Set<Long>> = sheetSync.syncingPerformanceIds
    val statisticsReport = statisticsState.report

    fun openPerformanceStatistics(performanceId: Long) {
        statisticsState.openPerformance(performanceId)
    }

    fun openActStatistics(actName: String) {
        statisticsState.openAct(actName)
    }

    fun closeStatistics() {
        statisticsState.close()
    }

    fun reserveAutomaticRefresh(performanceId: Long): Boolean =
        sheetSync.reserveAutomaticRefresh(performanceId)

    private fun launchTrackedOperation(operation: String, action: suspend () -> Unit) {
        sheetSync.launchTrackedOperation(operation, action)
    }

    private fun launchReservationMutation(
        operation: String,
        showProgress: Boolean = true,
        action: suspend () -> Unit
    ) {
        sheetSync.launchReservationMutation(operation, showProgress, action)
    }

    fun finishReservationEditing(performanceId: Long, spreadsheetUrl: String, accessToken: String?) {
        sheetSync.finishReservationEditing(performanceId, spreadsheetUrl, accessToken)
    }

    fun createPerformance(actName: String, date: String) {
        launchReservationMutation("create performance") {
            val performanceId = reservationRepository.createPerformance(actName, date)
            AppLog.info(LOG_COMPONENT) { "Created or selected performanceId=$performanceId, date=${date.trim()}" }
        }
    }

    fun selectPerformance(performanceId: Long) {
        launchReservationMutation("select performance") {
            reservationRepository.selectPerformance(performanceId)
            AppLog.info(LOG_COMPONENT) { "Selected performanceId=$performanceId" }
        }
    }

    fun deletePerformance(performanceId: Long) {
        launchReservationMutation("delete performance") {
            reservationRepository.deletePerformance(performanceId)
            AppLog.info(LOG_COMPONENT) { "Deleted performanceId=$performanceId" }
        }
    }

    fun deleteAct(actName: String) {
        launchTrackedOperation("delete local act data") {
            reservationRepository.deleteAct(actName)
        }
    }

    fun clearLocalData() {
        launchTrackedOperation("clear local app data") {
            reservationRepository.clearLocalData()
        }
    }

    fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation("add admission", showProgress = accessToken != null) {
            AppLog.debug(LOG_COMPONENT) {
                "Adding admission type=$admissionType, seats=$seatCount, " +
                    "reservedTypes=${reservedTicketAllocations.size}"
            }
            val reservationId = reservationRepository.addAdmission(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                seatCount = seatCount,
                admissionType = admissionType,
                reservedTicketAllocations = reservedTicketAllocations,
                accessToken = accessToken
            )
            AppLog.info(LOG_COMPONENT) {
                "Created reservationId=$reservationId, admission=$admissionType, seats=$seatCount"
            }
            addedReservationIdsChannel.send(reservationId)
        }
    }

    fun updateReservation(reservation: Reservation, accessToken: String? = null) {
        launchReservationMutation("update reservation", showProgress = accessToken != null) {
            AppLog.debug(LOG_COMPONENT) { "Updating ${reservation.toLogSummary()}" }
            reservationRepository.updateReservation(reservation, accessToken)
            AppLog.info(LOG_COMPONENT) { "Updated reservationId=${reservation.id}" }
        }
    }

    fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String? = null) {
        launchReservationMutation("update arrival count", showProgress = accessToken != null) {
            reservationRepository.updateArrivalCount(reservationId, arrivalCount, accessToken)
            AppLog.info(LOG_COMPONENT) { "Updated arrival count; reservationId=$reservationId, arrived=$arrivalCount" }
        }
    }

    fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation("add ticket sale", showProgress = accessToken != null) {
            AppLog.debug(LOG_COMPONENT) {
                "Adding ticket sale; reservationId=$reservationId, type=$ticketType, quantity=$quantity, " +
                    "payments=${payments.toPaymentLogSummary()}"
            }
            reservationRepository.addTicketSale(reservationId, ticketType, quantity, payments, accessToken)
            AppLog.info(LOG_COMPONENT) {
                "Added ticket sale; reservationId=$reservationId, type=$ticketType, quantity=$quantity"
            }
        }
    }

    fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation("update ticket sale", showProgress = accessToken != null) {
            AppLog.debug(LOG_COMPONENT) {
                "Updating ticketSaleId=$ticketSaleId, type=$ticketType, quantity=$quantity, " +
                    "payments=${payments.toPaymentLogSummary()}"
            }
            reservationRepository.updateTicketSale(ticketSaleId, ticketType, quantity, payments, accessToken)
            AppLog.info(LOG_COMPONENT) { "Updated ticketSaleId=$ticketSaleId" }
        }
    }

    fun deleteTicketSale(ticketSaleId: Long, accessToken: String? = null) {
        launchReservationMutation("delete ticket sale", showProgress = accessToken != null) {
            reservationRepository.deleteTicketSale(ticketSaleId, accessToken)
            AppLog.info(LOG_COMPONENT) { "Deleted ticketSaleId=$ticketSaleId" }
        }
    }

    fun deleteReservation(reservationId: Long, accessToken: String? = null) {
        launchReservationMutation("delete reservation") {
            reservationRepository.deleteReservation(reservationId, accessToken)
            AppLog.info(LOG_COMPONENT) { "Deleted reservationId=$reservationId" }
        }
    }

    fun deleteAllReservations(accessToken: String? = null) {
        launchReservationMutation("delete all reservations") {
            reservationRepository.deleteAllReservations(accessToken)
            AppLog.info(LOG_COMPONENT) { "Deleted all reservations from the active performance" }
        }
    }

    fun prepareGoogleSheetImport(spreadsheetUrl: String, accessToken: String) {
        sheetSync.prepareImport(spreadsheetUrl, accessToken)
    }

    fun applySheetFieldMappings(mappings: Map<String, SheetFieldClassification>) {
        sheetSync.applyFieldMappings(mappings)
    }

    fun dismissSheetMappingRequest() {
        sheetSync.dismissFieldMappingRequest()
    }

    fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String,
        showFeedback: Boolean = true
    ) {
        sheetSync.syncPerformance(
            performanceId,
            spreadsheetUrl,
            accessToken,
            showFeedback
        )
    }

    fun syncGoogleSheetPerformances(
        performances: List<Performance>,
        spreadsheetUrl: String,
        accessToken: String
    ) {
        sheetSync.syncPerformances(performances, spreadsheetUrl, accessToken)
    }

    fun saveGoogleSheetSource(actName: String, spreadsheetUrl: String) {
        sheetSync.saveSource(actName, spreadsheetUrl)
    }

    fun deleteGoogleSheetSource(actName: String) {
        sheetSync.deleteSource(actName)
    }

    fun dismissTransferMessage() {
        sheetSync.dismissTransferMessage()
    }

    companion object {
        private const val LOG_COMPONENT = "ViewModel"
        fun factory(repository: ReservationRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReservationViewModel(repository)
            }
        }

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
