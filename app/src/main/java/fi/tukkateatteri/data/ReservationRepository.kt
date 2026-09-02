package fi.tukkateatteri.data

import fi.tukkateatteri.data.local.ReservationDao
import fi.tukkateatteri.data.local.ReservationEntity
import fi.tukkateatteri.data.local.toEntity
import fi.tukkateatteri.data.local.toReservation
import fi.tukkateatteri.data.spreadsheet.ReservationSpreadsheetRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ReservationRepository {
    val reservations: Flow<List<Reservation>>

    suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        paymentMethod: PaymentMethod?
    )

    suspend fun updateReservation(reservation: Reservation)

    suspend fun deleteReservation(reservationId: Long)

    suspend fun exportSpreadsheetRows(): List<ReservationSpreadsheetRow>

    suspend fun importSpreadsheetRows(rows: List<ReservationSpreadsheetRow>)
}

class RoomReservationRepository(
    private val reservationDao: ReservationDao
) : ReservationRepository {
    override val reservations: Flow<List<Reservation>> = reservationDao.observeAll()
        .map { entities -> entities.map(ReservationEntity::toReservation) }

    override suspend fun addAdmission(
        lastName: String,
        firstName: String,
        contact: String,
        seatCount: Int,
        admissionType: AdmissionType,
        paymentMethod: PaymentMethod?
    ) {
        reservationDao.insert(
            ReservationEntity(
                lastName = lastName.trim(),
                firstName = firstName.trim(),
                contact = contact.trim(),
                seatCount = seatCount,
                admissionType = admissionType,
                isPresent = admissionType == AdmissionType.DOOR_SALE,
                paymentMethod = paymentMethod.takeIf { admissionType == AdmissionType.DOOR_SALE }
            )
        )
    }

    override suspend fun updateReservation(reservation: Reservation) {
        reservationDao.update(reservation.toEntity())
    }

    override suspend fun deleteReservation(reservationId: Long) {
        reservationDao.deleteById(reservationId)
    }

    override suspend fun exportSpreadsheetRows(): List<ReservationSpreadsheetRow> =
        reservationDao.getAll().map { entity ->
            ReservationSpreadsheetRow(
                lastName = entity.lastName,
                firstName = entity.firstName,
                contact = entity.contact,
                seatCount = entity.seatCount,
                admissionType = entity.admissionType,
                isPresent = entity.isPresent,
                paymentMethod = entity.paymentMethod
            )
        }

    override suspend fun importSpreadsheetRows(rows: List<ReservationSpreadsheetRow>) {
        reservationDao.insertAll(rows.map(ReservationSpreadsheetRow::toEntity))
    }
}

private fun ReservationSpreadsheetRow.toEntity() = ReservationEntity(
    lastName = lastName.trim(),
    firstName = firstName.trim(),
    contact = contact.trim(),
    seatCount = seatCount,
    admissionType = admissionType,
    isPresent = admissionType == AdmissionType.DOOR_SALE || isPresent,
    paymentMethod = paymentMethod.takeIf {
        admissionType == AdmissionType.DOOR_SALE || isPresent
    }
)
