package fi.tukkateatteri

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
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.GoogleSheetImportCandidate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReservationViewModel(
    private val reservationRepository: ReservationRepository
) : ViewModel() {
    private val addedReservationIdsChannel = Channel<Long>(Channel.BUFFERED)
    private val _transferMessage = MutableStateFlow<String?>(null)
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
    val transferMessage: StateFlow<String?> = _transferMessage
    val importCandidates: StateFlow<List<GoogleSheetImportCandidate>> = _importCandidates
    val isTransferInProgress: StateFlow<Boolean> = _isTransferInProgress

    fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType
    ) {
        viewModelScope.launch {
            val reservationId = reservationRepository.addAdmission(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                seatCount = seatCount,
                admissionType = admissionType
            )
            addedReservationIdsChannel.send(reservationId)
        }
    }

    fun updateReservation(reservation: Reservation) {
        viewModelScope.launch {
            reservationRepository.updateReservation(reservation)
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

    suspend fun exportSpreadsheetRows(): List<ReservationSpreadsheetRow> =
        reservationRepository.exportSpreadsheetRows()

    fun importSpreadsheetRows(rows: List<ReservationSpreadsheetRow>) {
        viewModelScope.launch {
            reservationRepository.importSpreadsheetRows(rows)
        }
    }

    fun prepareGoogleSheetImport(spreadsheetUrl: String, accessToken: String) {
        viewModelScope.launch {
            _isTransferInProgress.value = true
            runCatching { reservationRepository.loadGoogleSheetImportCandidates(spreadsheetUrl, accessToken) }
                .onSuccess { candidates ->
                    pendingImportUrl = spreadsheetUrl
                    pendingImportAccessToken = accessToken
                    _importCandidates.value = candidates
                    if (candidates.isEmpty()) _transferMessage.value = "Esitys:- ja Pvm:-tietoja sisältäviä välilehtiä ei löytynyt."
                }
                .onFailure { error -> _transferMessage.value = "Tuonti epäonnistui: ${error.message}" }
            _isTransferInProgress.value = false
        }
    }

    fun importGoogleSheet(candidate: GoogleSheetImportCandidate) {
        val spreadsheetUrl = pendingImportUrl ?: return
        val accessToken = pendingImportAccessToken ?: return
        viewModelScope.launch {
            _isTransferInProgress.value = true
            runCatching {
                reservationRepository.importGoogleSheet(spreadsheetUrl, accessToken, candidate.sheetTitle).also {
                    reservationRepository.upsertGoogleSheetSource(
                        GoogleSheetSource(candidate.performanceName, spreadsheetUrl)
                    )
                }
            }
                .onSuccess { count -> _transferMessage.value = "Tuotiin $count varausta." }
                .onFailure { error -> _transferMessage.value = "Tuonti epäonnistui: ${error.message}" }
            _isTransferInProgress.value = false
            _importCandidates.value = emptyList()
            pendingImportUrl = null
            pendingImportAccessToken = null
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
            runCatching { reservationRepository.exportGoogleSheet(spreadsheetUrl, sheetTitle, accessToken) }
                .onSuccess { _transferMessage.value = "Vienti onnistui." }
                .onFailure { error -> _transferMessage.value = "Vienti epäonnistui: ${error.message}" }
            _isTransferInProgress.value = false
        }
    }

    fun saveGoogleSheetSource(actName: String, spreadsheetUrl: String) {
        viewModelScope.launch {
            runCatching {
                reservationRepository.upsertGoogleSheetSource(
                    GoogleSheetSource(actName, spreadsheetUrl)
                )
            }.onFailure { error ->
                _transferMessage.value = "Google Sheets -osoitteen tallennus epäonnistui: ${error.message}"
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
