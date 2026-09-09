package fi.tukkateatteri

import android.util.Log
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
    val addedReservationIds = addedReservationIdsChannel.receiveAsFlow()
    val transferMessage: StateFlow<UiMessage?> = _transferMessage
    val isTransferInProgress: StateFlow<Boolean> = _isTransferInProgress

    private fun launchTrackedOperation(action: suspend () -> Unit) {
        viewModelScope.launch {
            activeTransferCount += 1
            _isTransferInProgress.value = true
            try {
                action()
            } finally {
                activeTransferCount -= 1
                _isTransferInProgress.value = activeTransferCount > 0
            }
        }
    }

    private fun launchReservationMutation(action: suspend () -> Unit) {
        launchTrackedOperation {
            try {
                action()
            } catch (_: GoogleSheetChangePendingException) {
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_change_pending)
            } catch (_: GoogleSheetLockedException) {
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_performance_locked)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                Log.e(TAG, "Reservation change failed", exception)
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_change_failed)
            }
        }
    }

    fun createPerformance(actName: String, date: String) {
        launchReservationMutation {
            reservationRepository.createPerformance(actName, date)
        }
    }

    fun selectPerformance(performanceId: Long) {
        launchReservationMutation {
            reservationRepository.selectPerformance(performanceId)
        }
    }

    fun deletePerformance(performanceId: Long) {
        launchReservationMutation {
            reservationRepository.deletePerformance(performanceId)
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
        launchReservationMutation {
            val reservationId = reservationRepository.addAdmission(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                seatCount = seatCount,
                admissionType = admissionType,
                reservedTicketAllocations = reservedTicketAllocations,
                accessToken = accessToken
            )
            addedReservationIdsChannel.send(reservationId)
        }
    }

    fun updateReservation(reservation: Reservation, accessToken: String? = null) {
        launchReservationMutation {
            reservationRepository.updateReservation(reservation, accessToken)
        }
    }

    fun updateArrivalCount(reservationId: Long, arrivalCount: Int, accessToken: String? = null) {
        launchReservationMutation {
            reservationRepository.updateArrivalCount(reservationId, arrivalCount, accessToken)
        }
    }

    fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation {
            reservationRepository.addTicketSale(reservationId, ticketType, quantity, payments, accessToken)
        }
    }

    fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>,
        accessToken: String? = null
    ) {
        launchReservationMutation {
            reservationRepository.updateTicketSale(ticketSaleId, ticketType, quantity, payments, accessToken)
        }
    }

    fun deleteTicketSale(ticketSaleId: Long, accessToken: String? = null) {
        launchReservationMutation {
            reservationRepository.deleteTicketSale(ticketSaleId, accessToken)
        }
    }

    fun deleteReservation(reservationId: Long, accessToken: String? = null) {
        launchReservationMutation {
            reservationRepository.deleteReservation(reservationId, accessToken)
        }
    }

    fun deleteAllReservations(accessToken: String? = null) {
        launchReservationMutation {
            reservationRepository.deleteAllReservations(accessToken)
        }
    }

    fun prepareGoogleSheetImport(spreadsheetUrl: String, accessToken: String) {
        launchTrackedOperation {
            try {
                val importResult = reservationRepository.importGoogleSheet(
                    spreadsheetUrl,
                    accessToken
                )
                _transferMessage.value = UiMessage.Plural(
                    messageResId = R.plurals.google_sheets_import_succeeded,
                    quantity = importResult.performanceCount,
                    formatArgs = listOf(importResult.performanceCount, importResult.reservationCount)
                )
            } catch (_: NoGoogleSheetImportCandidatesException) {
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_no_import_candidates)
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                Log.e(TAG, "Google Sheets import failed", exception)
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_import_failed)
            }
        }
    }

    fun syncGoogleSheetPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String,
        showError: Boolean = true
    ) {
        launchTrackedOperation {
            try {
                reservationRepository.syncGoogleSheetPerformance(
                    performanceId,
                    spreadsheetUrl,
                    accessToken
                )
            } catch (_: GoogleSheetSourceChangedException) {
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_sync_source_changed)
                }
            } catch (_: GoogleSheetLockedException) {
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_performance_locked)
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                Log.e(TAG, "Google Sheets performance sync failed", exception)
                if (showError) {
                    _transferMessage.value = UiMessage.Text(R.string.google_sheets_sync_failed)
                }
            }
        }
    }

    fun syncGoogleSheetPerformances(
        performanceIds: List<Long>,
        spreadsheetUrl: String,
        accessToken: String
    ) {
        launchTrackedOperation {
            try {
                performanceIds.forEach { performanceId ->
                    reservationRepository.syncGoogleSheetPerformance(
                        performanceId = performanceId,
                        spreadsheetUrl = spreadsheetUrl,
                        accessToken = accessToken
                    )
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                Log.e(TAG, "Google Sheets act import failed", exception)
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_sync_failed)
            }
        }
    }

    fun saveGoogleSheetSource(actName: String, spreadsheetUrl: String) {
        viewModelScope.launch {
            try {
                reservationRepository.upsertGoogleSheetSource(
                    GoogleSheetSource(actName, spreadsheetUrl)
                )
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                Log.e(TAG, "Saving Google Sheets source failed", exception)
                _transferMessage.value = UiMessage.Text(R.string.google_sheets_source_save_failed)
            }
        }
    }

    fun deleteGoogleSheetSource(actName: String) {
        viewModelScope.launch {
            reservationRepository.deleteGoogleSheetSource(actName)
        }
    }

    fun dismissTransferMessage() {
        _transferMessage.value = null
    }

    companion object {
        private const val TAG = "ReservationViewModel"
        fun factory(repository: ReservationRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReservationViewModel(repository)
            }
        }

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

private fun Exception.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
