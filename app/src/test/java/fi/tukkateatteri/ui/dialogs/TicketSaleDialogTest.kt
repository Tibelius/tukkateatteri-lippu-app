package fi.tukkateatteri.ui.dialogs

import fi.tukkateatteri.data.PaymentMethod
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
