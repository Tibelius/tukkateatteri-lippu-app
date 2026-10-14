package fi.tukkateatteri.data

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

    val hasMultiplePayments: Boolean
        get() = payments.size > 1

    val hasMultiplePaymentMethods: Boolean
        get() = payments.map(PaymentAllocation::method).distinct().size > 1

    val singlePaymentMethod: PaymentMethod?
        get() = payments.map(PaymentAllocation::method).distinct().singleOrNull()
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
    val amountCents: Int,
    val zettleSuccessful: Boolean = false
) {
    init {
        require(id > 0) { "Payment ID must be positive." }
        require(ticketSaleId > 0) { "Ticket sale ID must be positive." }
        require(amountCents >= 0) { "Payment amount must not be negative." }
    }

    val isLocked: Boolean
        get() = method.isExternallyConfirmed
}
