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

data class TicketType(
    val name: String,
    val label: String,
    val defaultPriceCents: Int,
    val sortOrder: Int = Int.MAX_VALUE
) {
    init {
        require(name.isNotBlank()) { "Ticket type identifier must not be blank." }
        require(label.isNotBlank()) { "Ticket type label must not be blank." }
        require(defaultPriceCents >= 0) { "Ticket price must not be negative." }
    }

    val displayLabel: String
        get() = if (defaultPriceCents == 0) {
            label
        } else {
            "$label ${defaultPriceCents.toCompactEuroString()}"
        }

    override fun equals(other: Any?): Boolean = other is TicketType && name == other.name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    companion object {
        val BASIC = TicketType("BASIC", "Perus", 2_200, 0)
        val DISCOUNT = TicketType("DISCOUNT", "Alennus", 1_300, 1)
        val THEATRE_INDUSTRY = TicketType("THEATRE_INDUSTRY", "Teatteriala", 1_000, 2)
        val MEMBER = TicketType("MEMBER", "Jäsen", 500, 3)
        val GROUP_BASIC = TicketType("GROUP_BASIC", "Ryhmä perus", 2_000, 4)
        val GROUP_DISCOUNT = TicketType("GROUP_DISCOUNT", "Ryhmä alennus", 1_200, 5)
        val KAIKUKORTTI = TicketType("KAIKUKORTTI", "Kaikukortti", 0, 6)
        val FREE_TICKET = TicketType("FREE_TICKET", "Vapaalippu", 0, 7)
        val UNSPECIFIED = TicketType("UNSPECIFIED", "Määrittelemätön", 0, Int.MAX_VALUE)

        val entries = listOf(
            BASIC,
            DISCOUNT,
            THEATRE_INDUSTRY,
            MEMBER,
            GROUP_BASIC,
            GROUP_DISCOUNT,
            KAIKUKORTTI,
            FREE_TICKET,
            UNSPECIFIED
        )

        fun valueOf(name: String): TicketType = entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown ticket type: $name")
    }
}

private fun Int.toCompactEuroString(): String =
    if (this % 100 == 0) "${this / 100} €" else toEuroString()
