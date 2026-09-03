package fi.tukkateatteri.data

import androidx.annotation.StringRes
import fi.tukkateatteri.R

data class Reservation(
    val id: Long,
    val performanceId: Long,
    val lastName: String,
    val firstName: String,
    val contact: String,
    val seatCount: Int,
    val notes: String = "",
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    val arrivalCount: Int = 0,
    val reservedTicketAllocations: List<ReservedTicketAllocation> = emptyList(),
    val ticketSales: List<TicketSale> = emptyList()
) {
    init {
        require(id > 0) { "Reservation ID must be positive." }
        require(performanceId > 0) { "Performance ID must be positive." }
        require(seatCount > 0) { "Seat count must be positive." }
        require(arrivalCount in 0..seatCount) { "Arrival count must be within the seat count." }
        require(reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity) <= seatCount) {
            "Reserved ticket quantities must not exceed the seat count."
        }
        require(reservedTicketAllocations.map(ReservedTicketAllocation::ticketType).distinct().size == reservedTicketAllocations.size) {
            "Each reserved ticket type may only appear once."
        }

        if (admissionType == AdmissionType.RESERVATION) {
            require(lastName.isNotBlank()) { "Last name must not be blank for reservations." }
            require(firstName.isNotBlank()) { "First name must not be blank for reservations." }
        }
    }

    val displayName: String
        get() = "$lastName $firstName"

    val paidSeatCount: Int
        get() = ticketSales.filter(TicketSale::isPaid).sumOf(TicketSale::quantity)

    val reservedTicketCount: Int
        get() = reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity)

    val unpaidSeatCount: Int
        get() = (seatCount - paidSeatCount).coerceAtLeast(0)

    val isPresent: Boolean
        get() = arrivalCount > 0

    val isFullyRedeemed: Boolean
        get() = paidSeatCount >= seatCount

    val isCompleted: Boolean
        get() = isFullyRedeemed && arrivalCount >= seatCount
}

enum class AdmissionType(@param:StringRes val labelResId: Int) {
    RESERVATION(R.string.admission_type_reservation),
    DOOR_SALE(R.string.admission_type_door_sale)
}

enum class PaymentMethod(@param:StringRes val labelResId: Int) {
    CARD(R.string.payment_method_card),
    CASH(R.string.payment_method_cash),
    EPASSI(R.string.payment_method_epassi),
    LIPPUAGENTTI(R.string.payment_method_lippuagentti)
}
