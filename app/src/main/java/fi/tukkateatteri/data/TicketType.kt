package fi.tukkateatteri.data

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

