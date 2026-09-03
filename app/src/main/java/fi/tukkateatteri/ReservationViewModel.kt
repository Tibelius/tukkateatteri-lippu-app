package fi.tukkateatteri

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.ReservedTicketAllocation
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.GoogleSheetImportCandidate
import fi.tukkateatteri.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val _importCandidates = MutableStateFlow<List<GoogleSheetImportCandidate>>(emptyList())
    private val _isTransferInProgress = MutableStateFlow(false)
    private var pendingImportUrl: String? = null
    private var pendingImportAccessToken: String? = null

    val reservations: StateFlow<List<Reservation>> = reservationRepository.reservations.stateIn(
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
    val importCandidates: StateFlow<List<GoogleSheetImportCandidate>> = _importCandidates
    val isTransferInProgress: StateFlow<Boolean> = _isTransferInProgress

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
                val candidates = reservationRepository.loadGoogleSheetImportCandidates(
                    spreadsheetUrl,
                    accessToken
                )
                if (candidates.isEmpty()) {
                    dismissImportCandidates()
                    _transferMessage.value = UiMessage(R.string.google_sheets_no_import_candidates)
                } else {
                    pendingImportUrl = spreadsheetUrl
                    pendingImportAccessToken = accessToken
                    _importCandidates.value = candidates
                }
            } catch (_: Exception) {
                _transferMessage.value = UiMessage(R.string.google_sheets_import_failed)
            } finally {
                _isTransferInProgress.value = false
            }
        }
    }

    fun importGoogleSheet(candidate: GoogleSheetImportCandidate) {
        val spreadsheetUrl = pendingImportUrl ?: return
        val accessToken = pendingImportAccessToken ?: return
        viewModelScope.launch {
            _isTransferInProgress.value = true
            try {
                val reservationCount = reservationRepository.importGoogleSheet(
                    spreadsheetUrl,
                    accessToken,
                    candidate.sheetTitle
                )
                reservationRepository.upsertGoogleSheetSource(
                    GoogleSheetSource(candidate.performanceName, spreadsheetUrl)
                )
                _transferMessage.value = UiMessage(
                    R.string.google_sheets_import_succeeded,
                    listOf(reservationCount)
                )
            } catch (_: Exception) {
                _transferMessage.value = UiMessage(R.string.google_sheets_import_failed)
            } finally {
                _isTransferInProgress.value = false
                dismissImportCandidates()
            }
        }
    }

    fun dismissImportCandidates() {
        _importCandidates.value = emptyList()
        pendingImportUrl = null
        pendingImportAccessToken = null
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
        fun factory(repository: ReservationRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReservationViewModel(repository)
            }
        }

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
