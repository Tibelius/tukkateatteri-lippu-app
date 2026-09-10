package fi.tukkateatteri

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.GoogleSheetChangePendingException
import fi.tukkateatteri.data.GoogleSheetSourceChangedException
import fi.tukkateatteri.data.NoGoogleSheetImportCandidatesException
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.GoogleSheetLockedException
import fi.tukkateatteri.data.spreadsheet.SheetFieldClassification
import fi.tukkateatteri.data.spreadsheet.SheetFieldMapping
import fi.tukkateatteri.data.spreadsheet.UnmappedSheetColumnsException
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toLogSummary
import fi.tukkateatteri.logging.toPaymentLogSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiMessage {
    data class Text(
        @param:StringRes val messageResId: Int,
        val formatArgs: List<Any> = emptyList()
    ) : UiMessage

    data class Plural(
        @param:PluralsRes val messageResId: Int,
        val quantity: Int,
        val formatArgs: List<Any> = emptyList()
    ) : UiMessage
}

class ReservationViewModel(
    private val reservationRepository: ReservationRepository
) : ViewModel() {
    private val addedReservationIdsChannel = Channel<Long>(Channel.BUFFERED)
    private val _transferMessage = MutableStateFlow<UiMessage?>(null)
    private val _isTransferInProgress = MutableStateFlow(false)
    private val _sheetMappingRequest = MutableStateFlow<SheetMappingRequest?>(null)
    private var pendingMappingOperation: PendingMappingOperation? = null
    private var activeTransferCount = 0

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
    val availablePaymentMethods = reservationRepository.availablePaymentMethods.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = fi.tukkateatteri.data.PaymentMethod.entries
    )
    val addedReservationIds = addedReservationIdsChannel.receiveAsFlow()
    val transferMessage: StateFlow<UiMessage?> = _transferMessage
    val isTransferInProgress: StateFlow<Boolean> = _isTransferInProgress
    val sheetMappingRequest: StateFlow<SheetMappingRequest?> = _sheetMappingRequest

    private fun launchTrackedOperation(operation: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            val startedAt = System.nanoTime()
            AppLog.debug(LOG_COMPONENT) { "Starting $operation" }
            activeTransferCount += 1
            _isTransferInProgress.value = true
            try {
                action()
                AppLog.debug(LOG_COMPONENT) {
                    "Completed $operation in ${AppLog.elapsedMillis(startedAt)} ms"
                }
            } catch (exception: CancellationException) {
                AppLog.debug(LOG_COMPONENT) { "Cancelled $operation after ${AppLog.elapsedMillis(startedAt)} ms" }
                throw exception
            } finally {
                activeTransferCount -= 1
                _isTransferInProgress.value = activeTransferCount > 0
                AppLog.verbose(LOG_COMPONENT) { "Active operations=$activeTransferCount" }
            }
        }
    }

    private fun launchReservationMutation(operation: String, action: suspend () -> Unit) {
        launchTrackedOperation(operation) {
            try {
                action()
            } catch (exception: GoogleSheetChangePendingException) {
                AppLog.warning(LOG_COMPONENT, exception) {
                    "$operation was saved locally but could not be synchronized"
                }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_change_pending)
            } catch (exception: GoogleSheetLockedException) {
                AppLog.warning(LOG_COMPONENT, exception) { "$operation could not acquire the performance lock" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_performance_locked)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "$operation failed" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_change_failed)
            }
        }
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

    fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation("add admission") {
            AppLog.debug(LOG_COMPONENT) {
                "Adding admission type=$admissionType, seats=$seatCount, reservedTypes=${reservedTicketAllocations.size}"
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
            AppLog.info(LOG_COMPONENT) { "Created reservationId=$reservationId, admission=$admissionType, seats=$seatCount" }
            addedReservationIdsChannel.send(reservationId)
        }
    }

    fun updateReservation(reservation: Reservation, accessToken: String? = null) {
        launchReservationMutation("update reservation") {
            AppLog.debug(LOG_COMPONENT) { "Updating ${reservation.toLogSummary()}" }
            reservationRepository.updateReservation(reservation, accessToken)
            AppLog.info(LOG_COMPONENT) { "Updated reservationId=${reservation.id}" }
        }
    }

    fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String? = null) {
        launchReservationMutation("update arrival count") {
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
        launchReservationMutation("add ticket sale") {
            AppLog.debug(LOG_COMPONENT) {
                "Adding ticket sale; reservationId=$reservationId, type=$ticketType, quantity=$quantity, " +
                    "payments=${payments.toPaymentLogSummary()}"
            }
            reservationRepository.addTicketSale(reservationId, ticketType, quantity, payments, accessToken)
            AppLog.info(LOG_COMPONENT) { "Added ticket sale; reservationId=$reservationId, type=$ticketType, quantity=$quantity" }
        }
    }

    fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation("update ticket sale") {
            AppLog.debug(LOG_COMPONENT) {
                "Updating ticketSaleId=$ticketSaleId, type=$ticketType, quantity=$quantity, " +
                    "payments=${payments.toPaymentLogSummary()}"
            }
            reservationRepository.updateTicketSale(ticketSaleId, ticketType, quantity, payments, accessToken)
            AppLog.info(LOG_COMPONENT) { "Updated ticketSaleId=$ticketSaleId" }
        }
    }

    fun deleteTicketSale(ticketSaleId: Long, accessToken: String? = null) {
        launchReservationMutation("delete ticket sale") {
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
        launchTrackedOperation("import spreadsheet") {
            try {
                AppLog.info(LOG_COMPONENT) { "Starting spreadsheet import" }
                val importResult = reservationRepository.importGoogleSheet(
                    spreadsheetUrl,
                    accessToken
                )
                AppLog.info(LOG_COMPONENT) {
                    "Spreadsheet import completed; performances=${importResult.performanceCount}, " +
                        "reservations=${importResult.reservationCount}"
                }
                _transferMessage.value = UiMessage.Plural(
                    messageResId = R.plurals.google_sheets_import_succeeded,
                    quantity = importResult.performanceCount,
                    formatArgs = listOf(importResult.performanceCount, importResult.reservationCount)
                )
            } catch (exception: NoGoogleSheetImportCandidatesException) {
                AppLog.warning(LOG_COMPONENT, exception) { "Spreadsheet contained no importable performances" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_no_import_candidates)
            } catch (exception: UnmappedSheetColumnsException) {
                AppLog.info(LOG_COMPONENT) { "Import needs ${exception.headers.size} column classifications" }
                requestSheetFieldMappings(exception, spreadsheetUrl, accessToken) {
                    val result = reservationRepository.importGoogleSheet(spreadsheetUrl, accessToken)
                    _transferMessage.value = UiMessage.Plural(
                        R.plurals.google_sheets_import_succeeded,
                        result.performanceCount,
                        listOf(result.performanceCount, result.reservationCount)
                    )
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "Spreadsheet import failed" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_import_failed)
            }
        }
    }

    fun applySheetFieldMappings(mappings: Map<String, SheetFieldClassification>) {
        val pending = pendingMappingOperation ?: return
        _sheetMappingRequest.value = null
        pendingMappingOperation = null
        launchTrackedOperation("save Sheet field mappings") {
            try {
                reservationRepository.saveSheetFieldMappings(
                    pending.spreadsheetUrl,
                    pending.accessToken,
                    mappings.map { (header, classification) -> SheetFieldMapping(header, classification) }
                )
                pending.retry()
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "Saving field mappings or retrying the original operation failed" }
                _transferMessage.value = UiMessage.Text(pending.failureMessageResId)
            }
        }
    }

    fun dismissSheetMappingRequest() {
        pendingMappingOperation = null
        _sheetMappingRequest.value = null
    }

    private fun requestSheetFieldMappings(
        exception: UnmappedSheetColumnsException,
        spreadsheetUrl: String,
        accessToken: String,
        failureMessageResId: Int = R.string.google_sheets_import_failed,
        retry: suspend () -> Unit
    ) {
        pendingMappingOperation = PendingMappingOperation(
            spreadsheetUrl = spreadsheetUrl,
            accessToken = accessToken,
            failureMessageResId = failureMessageResId,
            retry = retry
        )
        _sheetMappingRequest.value = SheetMappingRequest(exception.headers)
    }

    fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String,
        showError: Boolean = true
    ) {
        launchTrackedOperation("synchronize performance") {
            try {
                AppLog.info(LOG_COMPONENT) { "Starting synchronization for performanceId=$performanceId" }
                val rowCount = reservationRepository.syncGoogleSheetPerformance(
                    performanceId,
                    spreadsheetUrl,
                    accessToken
                )
                AppLog.info(LOG_COMPONENT) {
                    "Synchronized performanceId=$performanceId; receivedRows=$rowCount"
                }
            } catch (exception: GoogleSheetSourceChangedException) {
                AppLog.warning(LOG_COMPONENT, exception) {
                    "Performance source metadata no longer matches; performanceId=$performanceId"
                }
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_sync_source_changed)
                }
            } catch (exception: GoogleSheetLockedException) {
                AppLog.warning(LOG_COMPONENT, exception) {
                    "Performance synchronization could not acquire lock; performanceId=$performanceId"
                }
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_performance_locked)
                }
            } catch (exception: UnmappedSheetColumnsException) {
                AppLog.info(LOG_COMPONENT) {
                    "Performance synchronization needs ${exception.headers.size} column classifications; " +
                        "performanceId=$performanceId"
                }
                requestSheetFieldMappings(
                    exception = exception,
                    spreadsheetUrl = spreadsheetUrl,
                    accessToken = accessToken,
                    failureMessageResId = R.string.google_sheets_sync_failed
                ) {
                    val rowCount = reservationRepository.syncGoogleSheetPerformance(
                        performanceId,
                        spreadsheetUrl,
                        accessToken
                    )
                    AppLog.info(LOG_COMPONENT) {
                        "Synchronized performanceId=$performanceId after field classification; receivedRows=$rowCount"
                    }
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) {
                    "Performance synchronization failed; performanceId=$performanceId"
                }
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_sync_failed)
                }
            }
        }
    }

    fun syncGoogleSheetPerformances(
        performances: List<Performance>,
        spreadsheetUrl: String,
        accessToken: String
    ) {
        launchTrackedOperation("synchronize performances") {
            AppLog.info(LOG_COMPONENT) { "Starting synchronization for ${performances.size} performances" }
            val failedPerformances = buildList {
                performances.forEach { performance ->
                    try {
                        reservationRepository.syncGoogleSheetPerformance(
                            performanceId = performance.id,
                            spreadsheetUrl = spreadsheetUrl,
                            accessToken = accessToken
                        )
                    } catch (exception: UnmappedSheetColumnsException) {
                        AppLog.info(LOG_COMPONENT) {
                            "Performance batch synchronization needs ${exception.headers.size} column classifications; " +
                                "performanceId=${performance.id}"
                        }
                        requestSheetFieldMappings(
                            exception = exception,
                            spreadsheetUrl = spreadsheetUrl,
                            accessToken = accessToken,
                            failureMessageResId = R.string.google_sheets_sync_failed
                        ) {
                            syncGoogleSheetPerformances(performances, spreadsheetUrl, accessToken)
                        }
                        return@launchTrackedOperation
                    } catch (exception: Exception) {
                        exception.rethrowIfCancellation()
                        AppLog.error(LOG_COMPONENT, exception) {
                            "Performance synchronization failed; performanceId=${performance.id}, " +
                                "date=${performance.date}"
                        }
                        add(performance.displayName)
                    }
                }
            }
            if (failedPerformances.isNotEmpty()) {
                AppLog.warning(LOG_COMPONENT) {
                    "Performance batch synchronization completed with ${failedPerformances.size} failures"
                }
                _transferMessage.value = UiMessage.Text(
                    messageResId = R.string.google_sheets_sync_dates_failed,
                    formatArgs = listOf(failedPerformances.joinToString())
                )
            } else {
                AppLog.info(LOG_COMPONENT) {
                    "Performance batch synchronization completed; performances=${performances.size}"
                }
            }
        }
    }

    fun saveGoogleSheetSource(actName: String, spreadsheetUrl: String) {
        viewModelScope.launch {
            try {
                AppLog.debug(LOG_COMPONENT) { "Saving spreadsheet source; actConfigured=${actName.isNotBlank()}" }
                reservationRepository.upsertGoogleSheetSource(
                    GoogleSheetSource(actName, spreadsheetUrl)
                )
                AppLog.info(LOG_COMPONENT) { "Saved spreadsheet source" }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "Saving spreadsheet source failed" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_source_save_failed)
            }
        }
    }

    fun deleteGoogleSheetSource(actName: String) {
        viewModelScope.launch {
            try {
                reservationRepository.deleteGoogleSheetSource(actName)
                AppLog.info(LOG_COMPONENT) { "Deleted spreadsheet source" }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "Deleting spreadsheet source failed" }
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_source_save_failed)
            }
        }
    }

    fun dismissTransferMessage() {
        _transferMessage.value = null
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

data class SheetMappingRequest(val headers: List<String>)

private data class PendingMappingOperation(
    val spreadsheetUrl: String,
    val accessToken: String,
    @StringRes val failureMessageResId: Int,
    val retry: suspend () -> Unit
)

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
