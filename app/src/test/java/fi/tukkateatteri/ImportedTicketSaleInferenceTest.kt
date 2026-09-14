package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.inferImportedTicketSales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedTicketSaleInferenceTest {
    @Test
    fun onePaymentMethodCoveringAllReservationsRetainsEveryTicketType() {
        val sales = inferImportedTicketSales(
            reservedTicketCounts = mapOf(TicketType.BASIC to 1, TicketType.GROUP_BASIC to 2),
            paymentTicketCounts = mapOf(PaymentMethod.CARD to 3)
        ).orEmpty()

        assertEquals(2, sales.size)
        assertEquals(TicketType.BASIC, sales[0].ticketType)
        assertEquals(1, sales[0].quantity)
        assertEquals(PaymentMethod.CARD, sales[0].paymentMethod)
        assertEquals(TicketType.GROUP_BASIC, sales[1].ticketType)
        assertEquals(2, sales[1].quantity)
    }

    @Test
    fun oneTicketTypeCanBeSplitAcrossPaymentMethods() {
        val sales = inferImportedTicketSales(
            reservedTicketCounts = mapOf(TicketType.BASIC to 2),
            paymentTicketCounts = mapOf(PaymentMethod.CARD to 1, PaymentMethod.CASH to 1)
        ).orEmpty()

        assertEquals(2, sales.size)
        assertEquals(listOf(TicketType.BASIC, TicketType.BASIC), sales.map { it.ticketType })
        assertEquals(setOf(PaymentMethod.CARD, PaymentMethod.CASH), sales.map { it.paymentMethod }.toSet())
    }

    @Test
    fun singlePrepaidTicketRetainsItsTicketType() {
        val sales = inferImportedTicketSales(
            reservedTicketCounts = mapOf(TicketType.DISCOUNT to 1),
            paymentTicketCounts = mapOf(PaymentMethod.LIPPUAGENTTI to 1)
        ).orEmpty()

        assertEquals(1, sales.size)
        assertEquals(TicketType.DISCOUNT, sales.single().ticketType)
        assertEquals(PaymentMethod.LIPPUAGENTTI, sales.single().paymentMethod)
    }

    @Test
    fun multipleTicketTypesAndPaymentMethodsRemainUnassigned() {
        assertNull(
            inferImportedTicketSales(
                reservedTicketCounts = mapOf(TicketType.BASIC to 1, TicketType.DISCOUNT to 1),
                paymentTicketCounts = mapOf(PaymentMethod.CARD to 1, PaymentMethod.CASH to 1)
            )
        )
    }

    @Test
    fun mismatchingPaymentCountRemainsUnassigned() {
        assertNull(
            inferImportedTicketSales(
                reservedTicketCounts = mapOf(TicketType.BASIC to 1),
                paymentTicketCounts = mapOf(PaymentMethod.CARD to 2)
            )
        )
    }

    @Test
    fun noPaymentsProducesNoRealizedSales() {
        assertTrue(
            inferImportedTicketSales(
                reservedTicketCounts = mapOf(TicketType.BASIC to 2),
                paymentTicketCounts = emptyMap()
            ).orEmpty().isEmpty()
        )
    }

    @Test
    fun paymentWithoutTicketTypeCannotBeInferred() {
        assertNull(
            inferImportedTicketSales(
                reservedTicketCounts = emptyMap(),
                paymentTicketCounts = mapOf(PaymentMethod.CARD to 1)
            )
        )
    }

    @Test
    fun zeroAndNegativeMarkersAreIgnoredBeforeInference() {
        val sales = inferImportedTicketSales(
            reservedTicketCounts = mapOf(
                TicketType.BASIC to 1,
                TicketType.DISCOUNT to 0,
                TicketType.MEMBER to -2
            ),
            paymentTicketCounts = mapOf(
                PaymentMethod.CARD to 1,
                PaymentMethod.CASH to 0
            )
        ).orEmpty()

        assertEquals(1, sales.size)
        assertEquals(TicketType.BASIC, sales.single().ticketType)
        assertEquals(PaymentMethod.CARD, sales.single().paymentMethod)
    }

    @Test
    fun partialPaymentCountForOneTicketTypeCanBeInferred() {
        val sales = inferImportedTicketSales(
            reservedTicketCounts = mapOf(TicketType.BASIC to 3),
            paymentTicketCounts = mapOf(PaymentMethod.CARD to 2)
        ).orEmpty()

        assertEquals(1, sales.size)
        assertEquals(2, sales.single().quantity)
        assertEquals(TicketType.BASIC, sales.single().ticketType)
    }

    @Test
    fun severalTicketTypesWithOnlySomePaidCannotBePairedSafely() {
        assertNull(
            inferImportedTicketSales(
                reservedTicketCounts = mapOf(TicketType.BASIC to 2, TicketType.DISCOUNT to 1),
                paymentTicketCounts = mapOf(PaymentMethod.CARD to 2)
            )
        )
    }

    @Test
    fun everyBuiltInPaymentMethodCanCoverOneKnownTicketType() {
        PaymentMethod.entries.forEach { method ->
            val sale = requireNotNull(
                inferImportedTicketSales(
                    reservedTicketCounts = mapOf(TicketType.BASIC to 1),
                    paymentTicketCounts = mapOf(method to 1)
                )
            ).single()

            assertEquals(method, sale.paymentMethod)
            assertEquals(TicketType.BASIC, sale.ticketType)
        }
    }
}
