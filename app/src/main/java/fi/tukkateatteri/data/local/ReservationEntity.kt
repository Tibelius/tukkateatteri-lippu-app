package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.Reservation

@Entity(tableName = "reservations")
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "last_name")
    val lastName: String,
    @ColumnInfo(name = "first_name")
    val firstName: String,
    val contact: String,
    @ColumnInfo(name = "seat_count")
    val seatCount: Int,
    @ColumnInfo(name = "admission_type")
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    @ColumnInfo(name = "is_present")
    val isPresent: Boolean = false,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: PaymentMethod? = null
)

fun ReservationEntity.toReservation() = Reservation(
    id = id,
    lastName = lastName,
    firstName = firstName,
    contact = contact,
    seatCount = seatCount,
    admissionType = admissionType,
    isPresent = isPresent,
    paymentMethod = paymentMethod
)

fun Reservation.toEntity() = ReservationEntity(
    id = id,
    lastName = lastName,
    firstName = firstName,
    contact = contact,
    seatCount = seatCount,
    admissionType = admissionType,
    isPresent = isPresent,
    paymentMethod = paymentMethod
)
