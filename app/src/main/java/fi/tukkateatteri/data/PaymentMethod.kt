package fi.tukkateatteri.data

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
            PaymentMethodDefinition(LIPPUAGENTTI_ID, "Lippuagentti", allowsSplitPayment = false),
            PaymentMethodDefinition(EPASSI_ID, "ePassi")
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
