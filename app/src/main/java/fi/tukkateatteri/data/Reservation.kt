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

    val recordedSaleSeatCount: Int
        get() = ticketSales.sumOf(TicketSale::quantity)

    val reservedTicketCount: Int
        get() = reservedTicketAllocations.sumOf(ReservedTicketAllocation::quantity)

    val unpaidSeatCount: Int
        get() = (seatCount - paidSeatCount).coerceAtLeast(0)

    val availableTicketSaleSeatCount: Int
        get() = (seatCount - recordedSaleSeatCount).coerceAtLeast(0)

    val hasPartialPayment: Boolean
        get() = ticketSales.any { sale -> sale.paidAmountCents in 1 until sale.totalPriceCents }

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

    val allowsPartialPayment: Boolean
        get() = allowsSplitPayment

    companion object {
        private const val CARD_ID = "CARD"
        private const val CASH_ID = "CASH"
        private const val EPASSI_ID = "EPASSI"
        private const val LIPPUAGENTTI_ID = "LIPPUAGENTTI"

        private val defaultDefinitions = listOf(
            PaymentMethodDefinition(CARD_ID, "Kortti"),
            PaymentMethodDefinition(CASH_ID, "Käteinen"),
            PaymentMethodDefinition(EPASSI_ID, "ePassi"),
            PaymentMethodDefinition(LIPPUAGENTTI_ID, "Lippuagentti", allowsSplitPayment = false)
        )
        val entries = defaultDefinitions.mapIndexed { index, definition ->
            PaymentMethod(
                name = definition.name,
                label = definition.label,
                allowsSplitPayment = definition.allowsSplitPayment,
                sortOrder = index
            )
        }
        private val entriesByName = entries.associateBy(PaymentMethod::name)

        val CARD = entriesByName.getValue(CARD_ID)
        val CASH = entriesByName.getValue(CASH_ID)
        val EPASSI = entriesByName.getValue(EPASSI_ID)
        val LIPPUAGENTTI = entriesByName.getValue(LIPPUAGENTTI_ID)

        fun valueOf(name: String): PaymentMethod = entriesByName[name]
            ?: throw IllegalArgumentException("Unknown payment method: $name")

        fun displaySortOrder(method: PaymentMethod): Int {
            val builtInIndex = entries.indexOfFirst { it.name == method.name }
            if (builtInIndex >= 0) return builtInIndex
            return method.sortOrder.coerceAtMost(Int.MAX_VALUE - entries.size) + entries.size
        }
    }
}

val PaymentMethod.isExternallyConfirmed: Boolean
    get() = this == PaymentMethod.CARD || this == PaymentMethod.LIPPUAGENTTI

private data class PaymentMethodDefinition(
    val name: String,
    val label: String,
    val allowsSplitPayment: Boolean = true
)
