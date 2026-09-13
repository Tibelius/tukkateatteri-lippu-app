package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PendingPaymentAllocation
import fi.tukkateatteri.data.local.PaymentAllocationEntity
import fi.tukkateatteri.data.retainProtectedPayments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPaymentsTest {
    @Test
    fun confirmedTerminalPaymentMustRemainConfirmed() {
        val protected = paymentEntity(
            method = PaymentMethod.CARD,
            amountCents = 2_200,
            zettleSuccessful = true
        )

        assertFalse(
            listOf(PendingPaymentAllocation(PaymentMethod.CARD, 2_200, false))
                .retainProtectedPayments(listOf(protected))
        )
        assertTrue(
            listOf(PendingPaymentAllocation(PaymentMethod.CARD, 2_200, true))
                .retainProtectedPayments(listOf(protected))
        )
    }

    @Test
    fun externallyConfirmedPaymentCannotBeRemovedOrChanged() {
        val protected = paymentEntity(
            method = PaymentMethod.LIPPUAGENTTI,
            amountCents = 1_300,
            zettleSuccessful = false
        )

        assertFalse(emptyList<PendingPaymentAllocation>().retainProtectedPayments(listOf(protected)))
        assertFalse(
            listOf(PendingPaymentAllocation(PaymentMethod.LIPPUAGENTTI, 1_200, false))
                .retainProtectedPayments(listOf(protected))
        )
    }

    private fun paymentEntity(
        method: PaymentMethod,
        amountCents: Int,
        zettleSuccessful: Boolean
    ) = PaymentAllocationEntity(
        id = 1,
        ticketSaleId = 1,
        paymentMethod = method,
        amountCents = amountCents,
        zettleSuccessful = zettleSuccessful
    )
}
