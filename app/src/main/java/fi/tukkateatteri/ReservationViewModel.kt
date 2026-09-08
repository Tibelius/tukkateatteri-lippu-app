package fi.tukkateatteri

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.GoogleSheetSourceChangedException
import fi.tukkateatteri.data.NoGoogleSheetImportCandidatesException
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(
    @param:StringRes val messageResId: Int,
    val formatArgs: List<Any> = emptyList()
)

class ReservationViewModel(
    private val reservationRepository: ReservationRepository
) : ViewModel() {
    private val addedReservationIdsChannel = Channel<Long>(Channel.BUFFERED)
    private val _transferMessage = MutableStateFlow<UiMessage?>(null)
    private val _isTransferInProgress = MutableStateFlow(false)

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

    fun createPerformance(actName: String, date: String) {
        viewModelScope.launch {
            reservationRepository.createPerformance(actName, date)
        }
    }

    fun selectPerformance(performanceId: Long) {
        viewModelScope.launch {
            reservationRepository.selectPerformance(performanceId)
        }
    }

    fun deletePerformance(performanceId: Long) {
        viewModelScope.launch {
            reservationRepository.deletePerformance(performanceId)
        }
    }

    fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        reservedTicketAllocations: List<ReservedTicketAllocation>
    ) {
        viewModelScope.launch {
            val reservationId = reservationRepository.addAdmission(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                seatCount = seatCount,
                admissionType = admissionType,
                reservedTicketAllocations = reservedTicketAllocations
            )
            addedReservationIdsChannel.send(reservationId)
        }
    }

    fun updateReservation(reservation: Reservation) {
        viewModelScope.launch {
            reservationRepository.updateReservation(reservation)
        }
    }

    fun updateArrivalCount(reservationId: Long, arrivalCount: Int) {
        viewModelScope.launch {
            reservationRepository.updateArrivalCount(reservationId, arrivalCount)
        }
    }

    fun addTicketSale(
        reservationId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) {
        viewModelScope.launch {
            reservationRepository.addTicketSale(reservationId, ticketType, quantity, payments)
        }
    }

    fun updateTicketSale(
        ticketSaleId: Long,
        ticketType: TicketType,
        quantity: Int,
        payments: List<PendingPaymentAllocation>
    ) {
        viewModelScope.launch {
            reservationRepository.updateTicketSale(ticketSaleId, ticketType, quantity, payments)
        }
    }

    fun deleteTicketSale(ticketSaleId: Long) {
        viewModelScope.launch {
            reservationRepository.deleteTicketSale(ticketSaleId)
        }
    }

    fun deleteReservation(reservationId: Long) {
        viewModelScope.launch {
            reservationRepository.deleteReservation(reservationId)
        }
    }

    fun deleteAllReservations() {
        viewModelScope.launch {
            reservationRepository.deleteAllReservations()
        }
    }

    fun prepareGoogleSheetImport(spreadsheetUrl: String, accessToken: String) {
        viewModelScope.launch {
            _isTransferInProgress.value = true
            try {
                val importResult = reservationRepository.importGoogleSheet(
                    spreadsheetUrl,
                    accessToken
                )
                _transferMessage.value = UiMessage(
                    R.string.google_sheets_import_succeeded,
                    listOf(importResult.performanceCount, importResult.reservationCount)
                )
            } catch (_: NoGoogleSheetImportCandidatesException) {
                _transferMessage.value = UiMessage(R.string.google_sheets_no_import_candidates)
            } catch (_: Exception) {
                _transferMessage.value = UiMessage(R.string.google_sheets_import_failed)
            } finally {
                _isTransferInProgress.value = false
            }
        }
    }

    fun syncGoogleSheetPerformance(performanceId: Long, spreadsheetUrl: String, accessToken: String) {
        viewModelScope.launch {
            _isTransferInProgress.value = true
            try {
                val reservationCount = reservationRepository.syncGoogleSheetPerformance(
                    performanceId,
                    spreadsheetUrl,
                    accessToken
                )
                _transferMessage.value = UiMessage(
                    R.string.google_sheets_sync_succeeded,
                    listOf(reservationCount)
                )
            } catch (_: GoogleSheetSourceChangedException) {
                _transferMessage.value = UiMessage(R.string.google_sheets_sync_source_changed)
            } catch (exception: Exception) {
                Log.e(TAG, "Google Sheets performance sync failed", exception)
                _transferMessage.value = UiMessage(R.string.google_sheets_sync_failed)
            } finally {
                _isTransferInProgress.value = false
            }
        }
    }

    fun exportGoogleSheet(spreadsheetUrl: String, sheetTitle: String, accessToken: String) {
        viewModelScope.launch {
            _isTransferInProgress.value = true
            try {
                reservationRepository.exportGoogleSheet(spreadsheetUrl, sheetTitle, accessToken)
                _transferMessage.value = UiMessage(R.string.google_sheets_export_succeeded)
            } catch (_: Exception) {
                _transferMessage.value = UiMessage(R.string.google_sheets_export_failed)
            } finally {
                _isTransferInProgress.value = false
            }
        }
    }

    fun saveGoogleSheetSource(actName: String, spreadsheetUrl: String) {
        viewModelScope.launch {
            try {
                reservationRepository.upsertGoogleSheetSource(
                    GoogleSheetSource(actName, spreadsheetUrl)
                )
            } catch (_: Exception) {
                _transferMessage.value = UiMessage(R.string.google_sheets_source_save_failed)
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
