package fi.tukkateatteri.ui.dialogs

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.PaymentAllocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSaleDialogTest {
    @Test
    fun terminalChargeIsAvailableForValidCardAllocation() {
        assertTrue(terminalChargeEligibility())
    }

    @Test
    fun terminalChargeIsUnavailableForManualOrUnsafeCases() {
        assertFalse(terminalChargeEligibility(isGatewayAvailable = false))
        assertFalse(terminalChargeEligibility(hasCardPayment = false))
        assertFalse(terminalChargeEligibility(totalPriceCents = 0))
        assertFalse(terminalChargeEligibility(quantity = 0))
        assertFalse(terminalChargeEligibility(quantity = 3, maximumQuantity = 2))
        assertFalse(terminalChargeEligibility(paymentAmountCents = 0))
        assertFalse(terminalChargeEligibility(paymentAmountCents = 2_201))
        assertTrue(terminalChargeEligibility(paymentAmountCents = 2_000))
    }

    @Test
    fun externallyConfirmedPaymentCannotBeEdited() {
        val payment = StoredPayment(
            methodName = PaymentMethod.CARD.name,
            amountCents = 2_200,
            zettleSuccessful = true
        )

        assertNull(listOf(payment).editablePaymentAt(0, listOf(PaymentMethod.CARD)))
    }

    @Test
    fun configuredCustomPaymentCanBeEdited() {
        val customMethod = PaymentMethod(name = "SMARTUM", label = "Smartum")
        val payment = StoredPayment(
            methodName = customMethod.name,
            amountCents = 1_300,
            zettleSuccessful = false
        )

        assertEquals(payment, listOf(payment).editablePaymentAt(0, listOf(customMethod)))
    }

    @Test
    fun storedPaymentConvertsBackUsingConfiguredMethodIdentity() {
        val customMethod = PaymentMethod(name = "SMARTUM", label = "Smartum")
        val stored = StoredPayment("SMARTUM", 1_300, zettleSuccessful = false)

        val pending = requireNotNull(stored.toPending(listOf(PaymentMethod.CARD, customMethod)))

        assertEquals(customMethod, pending.method)
        assertEquals(1_300, pending.amountCents)
        assertFalse(pending.zettleSuccessful)
    }

    @Test
    fun storedPaymentWithRemovedMethodCannotBeRestoredOrEdited() {
        val stored = StoredPayment("REMOVED", 1_300, zettleSuccessful = false)

        assertNull(stored.toPending(listOf(PaymentMethod.CARD)))
        assertEquals(stored, listOf(stored).editablePaymentAt(0, emptyList()))
        assertNull(listOf(stored).editablePaymentAt(1, emptyList()))
    }

    @Test
    fun paymentAllocationRetainsTerminalConfirmationWhenStored() {
        val stored = PaymentAllocation(
            id = 7,
            ticketSaleId = 2,
            method = PaymentMethod.CARD,
            amountCents = 2_200,
            zettleSuccessful = true
        ).toStoredPayment()

        assertEquals("CARD", stored.methodName)
        assertEquals(2_200, stored.amountCents)
        assertTrue(stored.zettleSuccessful)
    }

    @Test
    fun allExternallyConfirmedMethodsAreProtectedFromEditing() {
        listOf(PaymentMethod.CARD, PaymentMethod.LIPPUAGENTTI).forEach { method ->
            val stored = StoredPayment(method.name, 100, zettleSuccessful = false)
            assertTrue(stored.isLocked(PaymentMethod.entries))
            assertNull(listOf(stored).editablePaymentAt(0, PaymentMethod.entries))
        }
    }

    private fun terminalChargeEligibility(
        isGatewayAvailable: Boolean = true,
        hasCardPayment: Boolean = true,
        totalPriceCents: Int = 2_200,
        quantity: Int = 1,
        maximumQuantity: Int = 1,
        paymentAmountCents: Int = totalPriceCents
    ): Boolean = canChargeWithTerminal(
        isGatewayAvailable = isGatewayAvailable,
        hasCardPayment = hasCardPayment,
        totalPriceCents = totalPriceCents,
        quantity = quantity,
        maximumQuantity = maximumQuantity,
        paymentAmountCents = paymentAmountCents
    )
}
