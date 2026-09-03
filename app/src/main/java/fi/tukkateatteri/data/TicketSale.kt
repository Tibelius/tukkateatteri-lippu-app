package fi.tukkateatteri.data

import androidx.annotation.StringRes
import fi.tukkateatteri.R

data class TicketSale(
    val id: Long,
    val reservationId: Long,
    val ticketType: TicketType,
    val quantity: Int,
    val unitPriceCents: Int,
    val origin: TicketSaleOrigin = TicketSaleOrigin.MANUAL,
    val countsAsArrival: Boolean = true,
    val payments: List<PaymentAllocation>
) {
    init {
        require(id > 0) { "Ticket sale ID must be positive." }
        require(reservationId > 0) { "Reservation ID must be positive." }
        require(quantity > 0) { "Ticket quantity must be positive." }
        require(unitPriceCents >= 0) { "Ticket price must not be negative." }
        require(payments.sumOf(PaymentAllocation::amountCents) <= totalPriceCents) {
            "Payment amount must not exceed the ticket price."
        }
    }

    val totalPriceCents: Int
        get() = quantity * unitPriceCents

    val paidAmountCents: Int
        get() = payments.sumOf(PaymentAllocation::amountCents)

    val isPaid: Boolean
        get() = paidAmountCents == totalPriceCents

    val isSplitPayment: Boolean
        get() = payments.size > 1

    val singlePaymentMethod: PaymentMethod?
        get() = payments.singleOrNull()?.method
}

enum class TicketSaleOrigin {
    MANUAL,
    IMPORTED
}

data class ReservedTicketAllocation(
    val ticketType: TicketType,
    val quantity: Int
) {
    init {
        require(ticketType != TicketType.UNSPECIFIED) {
            "Reserved ticket allocations need a ticket type."
        }
        require(quantity > 0) { "Reserved ticket quantity must be positive." }
    }
}

data class PaymentAllocation(
    val id: Long,
    val ticketSaleId: Long,
    val method: PaymentMethod,
    val amountCents: Int
) {
    init {
        require(id > 0) { "Payment ID must be positive." }
        require(ticketSaleId > 0) { "Ticket sale ID must be positive." }
        require(amountCents >= 0) { "Payment amount must not be negative." }
    }
}

enum class TicketType(
    @param:StringRes val labelResId: Int,
    val defaultPriceCents: Int
) {
    BASIC(R.string.ticket_type_basic, 2_200),
    DISCOUNT(R.string.ticket_type_discount, 1_300),
    THEATRE_INDUSTRY(R.string.ticket_type_theatre_industry, 1_000),
    MEMBER(R.string.ticket_type_member, 500),
    GROUP_BASIC(R.string.ticket_type_group_basic, 2_000),
    GROUP_DISCOUNT(R.string.ticket_type_group_discount, 1_200),
    KAIKUKORTTI(R.string.ticket_type_kaikukortti, 0),
    FREE_TICKET(R.string.ticket_type_free_ticket, 0),
    UNSPECIFIED(R.string.ticket_type_unspecified, 0)
}
