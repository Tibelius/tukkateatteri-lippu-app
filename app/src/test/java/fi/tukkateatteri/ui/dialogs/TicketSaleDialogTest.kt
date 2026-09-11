package fi.tukkateatteri.ui.dialogs

import fi.tukkateatteri.data.PaymentMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSaleDialogTest {
    @Test
    fun terminalChargeIsAvailableForValidNewCardSale() {
        assertTrue(terminalChargeEligibility())
    }

    @Test
    fun terminalChargeIsUnavailableForManualOrUnsafeCases() {
        assertFalse(terminalChargeEligibility(isGatewayAvailable = false))
        assertFalse(terminalChargeEligibility(isNewSale = false))
        assertFalse(terminalChargeEligibility(isSplitPayment = true))
        assertFalse(terminalChargeEligibility(selectedPayment = PaymentMethod.CASH))
        assertFalse(terminalChargeEligibility(totalPriceCents = 0))
        assertFalse(terminalChargeEligibility(quantity = 0))
        assertFalse(terminalChargeEligibility(quantity = 3, maximumQuantity = 2))
        assertFalse(terminalChargeEligibility(isPaymentValid = false))
    }

    private fun terminalChargeEligibility(
        isGatewayAvailable: Boolean = true,
        isNewSale: Boolean = true,
        isSplitPayment: Boolean = false,
        selectedPayment: PaymentMethod = PaymentMethod.CARD,
        totalPriceCents: Int = 2_200,
        quantity: Int = 1,
        maximumQuantity: Int = 1,
        isPaymentValid: Boolean = true
    ): Boolean = canChargeWithTerminal(
        isGatewayAvailable = isGatewayAvailable,
        isNewSale = isNewSale,
        isSplitPayment = isSplitPayment,
        selectedPayment = selectedPayment,
        totalPriceCents = totalPriceCents,
        quantity = quantity,
        maximumQuantity = maximumQuantity,
        isPaymentValid = isPaymentValid
    )
}
