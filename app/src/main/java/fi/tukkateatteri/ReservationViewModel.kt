package fi.tukkateatteri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReservationViewModel(
    private val reservationRepository: ReservationRepository
) : ViewModel() {
    private val addedReservationIdsChannel = Channel<Long>(Channel.BUFFERED)

    val reservations: StateFlow<List<Reservation>> = reservationRepository.reservations.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )
    val addedReservationIds = addedReservationIdsChannel.receiveAsFlow()

    fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        paymentMethod: PaymentMethod?
    ) {
        viewModelScope.launch {
            val reservationId = reservationRepository.addAdmission(
                lastName = lastName,
                firstName = firstName,
                contact = contact,
                seatCount = seatCount,
                admissionType = admissionType,
                paymentMethod = paymentMethod
            )
            if (admissionType == AdmissionType.RESERVATION) {
                addedReservationIdsChannel.send(reservationId)
            }
        }
    }

    fun updateReservation(reservation: Reservation) {
        viewModelScope.launch {
            reservationRepository.updateReservation(reservation)
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

    companion object {
        fun factory(repository: ReservationRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReservationViewModel(repository)
            }
        }

        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
