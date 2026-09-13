package fi.tukkateatteri.data

import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.local.ReservationWithTicketSales

internal fun List<PendingPaymentAllocation>.retainProtectedPayments(
    protectedPayments: List<PaymentAllocationEntity>
): Boolean {
    val unmatchedPayments = toMutableList()
    return protectedPayments.all { protected ->
        val matchIndex = unmatchedPayments.indexOfFirst { pending ->
            pending.method == protected.paymentMethod &&
                pending.amountCents == protected.amountCents &&
                (!protected.zettleSuccessful || pending.zettleSuccessful)
        }
        if (matchIndex < 0) {
            false
        } else {
            unmatchedPayments.removeAt(matchIndex)
            true
        }
    }
}

internal val PaymentAllocationEntity.isLocked: Boolean
    get() = paymentMethod.isExternallyConfirmed

internal fun ReservationWithTicketSales.hasLockedPayment(): Boolean =
    ticketSales.any { sale -> sale.payments.any(PaymentAllocationEntity::isLocked) }
