package fi.tukkateatteri.data

internal data class InferredImportedTicketSale(
    val ticketType: TicketType,
    val paymentMethod: PaymentMethod,
    val quantity: Int
)

/**
 * Restores ticket types from aggregate Sheet columns only when the pairing is unambiguous.
 * A single payment method can cover several ticket types, and a single ticket type can be
 * divided between several payment methods. When both sides contain several entries, the Sheet
 * row does not contain enough information to reconstruct the original sales safely.
 */
internal fun inferImportedTicketSales(
    reservedTicketCounts: Map<TicketType, Int>,
    paymentTicketCounts: Map<PaymentMethod, Int>
): List<InferredImportedTicketSale>? {
    val tickets = reservedTicketCounts.filterValues { it > 0 }
    val payments = paymentTicketCounts.filterValues { it > 0 }
    if (payments.isEmpty()) return emptyList()
    if (tickets.isEmpty()) return null

    val reservedTotal = tickets.values.sum()
    val paidTotal = payments.values.sum()
    if (paidTotal > reservedTotal) return null

    if (payments.size == 1 && paidTotal == reservedTotal) {
        val method = payments.keys.single()
        return tickets.map { (ticketType, quantity) ->
            InferredImportedTicketSale(ticketType, method, quantity)
        }
    }

    if (tickets.size == 1) {
        val ticketType = tickets.keys.single()
        return payments.map { (method, quantity) ->
            InferredImportedTicketSale(ticketType, method, quantity)
        }
    }

    return null
}
