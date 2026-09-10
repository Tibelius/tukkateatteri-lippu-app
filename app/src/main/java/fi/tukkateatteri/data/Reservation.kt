package fi.tukkateatteri.data

import androidx.annotation.StringRes
import fi.tukkateatteri.R

const val MINIMUM_SEAT_COUNT = 1

data class Reservation(
    val id: Long,
    val performanceId: Long,
    val lastName: String,
    val firstName: String,
    val contact: String,
    val seatCount: Int,
    val notes: String = "",
    val sourceIdentity: String = "",
    val sheetRowId: String = "",
    val syncState: ReservationSyncState = ReservationSyncState.SYNCED,
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    val arrivalCount: Int = 0,
    val reservedTicketAllocations: List<ReservedTicketAllocation> = emptyList(),
    val ticketSales: List<TicketSale> = emptyList()
) {
    init {
        require(id > 0) { "Reservation ID must be positive." }
        require(performanceId > 0) { "Performance ID must be positive." }
        require(seatCount >= MINIMUM_SEAT_COUNT) { "Seat count must be positive." }
        require(arrivalCount in 0..seatCount) { "Arrival count must be within the seat count." }
        require(reservedTicketAllocations.map(ReservedTicketAllocation::ticketType).distinct().size == reservedTicketAllocations.size) {
            "Each reserved ticket type may only appear once."
        }

        if (admissionType == AdmissionType.RESERVATION) {
            require(lastName.isNotBlank()) { "Last name must not be blank for reservations." }
            require(firstName.isNotBlank()) { "First name must not be blank for reservations." }
        }
    }

    val displayName: String
        get() = listOf(lastName, firstName)
            .filter(String::isNotBlank)
            .joinToString(separator = " ")

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

    /**
     * A complete reservation whose originally reserved ticket types and realized ticket types
     * have different total values. An incomplete type allocation cannot be checked reliably.
     */
    val hasTicketValueMismatch: Boolean
        get() = isFullyRedeemed &&
            ticketSales.none { ticketSale -> ticketSale.origin == TicketSaleOrigin.IMPORTED } &&
            reservedTicketCount == seatCount &&
            reservedTicketAllocations.sumOf { allocation ->
                allocation.ticketType.defaultPriceCents * allocation.quantity
            } != ticketSales.sumOf(TicketSale::totalPriceCents)

    val isCompleted: Boolean
        get() = isFullyRedeemed && arrivalCount >= seatCount
}

/** Describes whether this device has safely applied its latest local change to Google Sheets. */
enum class ReservationSyncState {
    SYNCED,
    PENDING,
    CONFLICT,
    PENDING_DELETION
}

enum class AdmissionType(@param:StringRes val labelResId: Int) {
    RESERVATION(R.string.admission_type_reservation),
    DOOR_SALE(R.string.admission_type_door_sale)
}

data class PaymentMethod(
    val name: String,
    val label: String,
    val allowsSplitPayment: Boolean = true,
    val sortOrder: Int = Int.MAX_VALUE
) {
    init {
        require(name.isNotBlank()) { "Payment method identifier must not be blank." }
        require(label.isNotBlank()) { "Payment method label must not be blank." }
    }

    override fun equals(other: Any?): Boolean = other is PaymentMethod && name == other.name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        val CARD = PaymentMethod("CARD", "Kortti", sortOrder = 0)
        val CASH = PaymentMethod("CASH", "Käteinen", sortOrder = 1)
        val EPASSI = PaymentMethod("EPASSI", "ePassi", sortOrder = 2)
        val LIPPUAGENTTI = PaymentMethod("LIPPUAGENTTI", "Lippuagentti", allowsSplitPayment = false, sortOrder = 3)
        val entries = listOf(CARD, CASH, EPASSI, LIPPUAGENTTI)

        fun valueOf(name: String): PaymentMethod = entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown payment method: $name")
    }
}
