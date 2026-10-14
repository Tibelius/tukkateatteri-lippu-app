package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetColumnSchemaTest {
    @Test
    fun exportUsesPreviouslyClassifiedAndIgnoredColumns() {
        val headers = baseHeaders("Ylläri", "Sisäinen", "KORTTI", "SMARTUM")
        val schema = SheetColumnSchema.fromOrderedHeaders(
            headers,
            mapOf(
                "ylläri" to alias(SheetFieldClassification.TICKET),
                "sisäinen" to alias(SheetFieldClassification.IGNORE),
                "smartum" to alias(SheetFieldClassification.PAYMENT)
            )
        )
        val row = ReservationSpreadsheetRow(
            lastName = "Virtanen",
            firstName = "Maija",
            contact = "",
            reservedSeatCount = 1,
            arrivalCount = 0,
            reservedTicketCounts = mapOf(schema.ticketTypes.single() to 1),
            paymentAmountsCents = mapOf(schema.paymentMethods.last() to 1_250),
            notes = ""
        )

        val values = row.toSheetCellValues("24.10", 2, headers.canonicalHeaderIndexes(), schema)

        assertTrue(values.any { it.range.endsWith("E2") && it.value == true })
        assertTrue(values.any { it.range.endsWith("H2") && it.value == 12.5 })
        assertFalse(values.any { it.range.endsWith("F2") })
    }

    @Test
    fun ticketPriceOverflowIsRejectedInsteadOfBecomingAnotherPrice() {
        assertThrows(IllegalArgumentException::class.java) {
            SheetColumnSchema.fromOrderedHeaders(baseHeaders("Perus 999999999999 €", "KORTTI"))
        }
    }

    @Test
    fun knownColumnsAreClassifiedAndOrderedByPhysicalPosition() {
        val schema = SheetColumnSchema.fromOrderedHeaders(
            baseHeaders("Perus 22 €", "Alennus 13,50 EUR", "Kaikukortti", "KORTTI", "KÄTEINEN")
        )

        assertEquals(
            listOf(TicketType.BASIC.name, TicketType.DISCOUNT.name, TicketType.KAIKUKORTTI.name),
            schema.ticketTypes.map(TicketType::name)
        )
        assertEquals(listOf(2_200, 1_350, 0), schema.ticketTypes.map(TicketType::defaultPriceCents))
        assertEquals(listOf(PaymentMethod.CARD.name, PaymentMethod.CASH.name), schema.paymentMethods.map { it.name })
    }

    @Test
    fun customPricedTicketBeforePaymentsIsAcceptedWithoutAlias() {
        val schema = SheetColumnSchema.fromOrderedHeaders(
            baseHeaders("Kannatuslippu 99,95 €", "KORTTI")
        )

        val ticket = schema.ticketTypes.single()
        assertEquals("SHEET_TICKET:kannatuslippu", ticket.name)
        assertEquals("Kannatuslippu", ticket.label)
        assertEquals(9_995, ticket.defaultPriceCents)
    }

    @Test
    fun unknownUnpricedColumnRequiresClassification() {
        val error = assertThrows(UnmappedSheetColumnsException::class.java) {
            SheetColumnSchema.fromOrderedHeaders(baseHeaders("Ylläri", "KORTTI"))
        }

        assertEquals(listOf("Ylläri"), error.headers)
    }

    @Test
    fun storedTicketAliasCanDefineAFreeTicket() {
        val schema = SheetColumnSchema.fromOrderedHeaders(
            headers = baseHeaders("Ylläri", "KORTTI"),
            storedAliases = mapOf(
                "ylläri" to StoredSheetAlias(
                    kind = SheetFieldClassification.TICKET,
                    label = "Yllätyslippu",
                    priceCents = 0,
                    allowsSplitPayment = true
                )
            )
        )

        assertEquals("Ylläri", schema.ticketTypes.single().label)
        assertEquals(0, schema.ticketTypes.single().defaultPriceCents)
    }

    @Test
    fun ignoredColumnsDoNotBecomeTicketOrPaymentDefinitions() {
        val schema = SheetColumnSchema.fromOrderedHeaders(
            headers = baseHeaders("Sisäinen merkintä", "KORTTI", "Kampanjakoodi"),
            storedAliases = mapOf(
                "sisäinen merkintä" to alias(SheetFieldClassification.IGNORE),
                "kampanjakoodi" to alias(SheetFieldClassification.IGNORE)
            )
        )

        assertTrue(schema.ticketTypes.isEmpty())
        assertEquals(listOf(PaymentMethod.CARD.name), schema.paymentMethods.map { it.name })
    }

    @Test
    fun customPaymentAliasAfterKnownPaymentRetainsConfiguration() {
        val schema = SheetColumnSchema.fromOrderedHeaders(
            headers = baseHeaders("Perus 22 €", "KORTTI", "SMARTUM"),
            storedAliases = mapOf(
                "smartum" to StoredSheetAlias(
                    kind = SheetFieldClassification.PAYMENT,
                    label = "Smartum mobiili",
                    priceCents = null,
                    allowsSplitPayment = false
                )
            )
        )

        val smartum = schema.paymentMethods.single { it.name == "SHEET_PAYMENT:smartum" }
        assertEquals("Smartum mobiili", smartum.label)
        assertFalse(smartum.allowsSplitPayment)
    }

    @Test
    fun columnsAfterNotesAreNeverTreatedAsOperationalFields() {
        val headers = baseHeaders("Perus 22 €", "KORTTI") + listOf("Developer data", "Another field")

        val schema = SheetColumnSchema.fromOrderedHeaders(headers)

        assertEquals(1, schema.ticketTypes.size)
        assertEquals(1, schema.paymentMethods.size)
    }

    @Test
    fun storedAliasesParserSkipsMalformedRowsAndNormalizesValidAliases() {
        val tab = GoogleSheetTab(
            title = APPLICATION_SHEET_TITLE,
            rows = listOf(
                listOf(ALIAS_HEADER, ALIAS_NORMALIZED_HEADER, ALIAS_KIND_HEADER, ALIAS_LABEL_HEADER, ALIAS_PRICE_HEADER, ALIAS_OPTIONS_HEADER),
                listOf("Ylläri", "  YLLÄRI  ", "ticket", "Yllätyslippu", "0", ""),
                listOf("Smartum", "SMARTUM", "PAYMENT", "Smartum mobiili", "", "split=false"),
                listOf("Broken", "broken", "NOT_A_KIND", "Broken", "", ""),
                listOf("Blank", "", "TICKET", "Blank", "", "")
            )
        )

        val aliases = tab.storedAliases()

        assertEquals(setOf("ylläri", "smartum"), aliases.keys)
        assertEquals(0, aliases.getValue("ylläri").priceCents)
        assertFalse(aliases.getValue("smartum").allowsSplitPayment)
    }

    @Test
    fun everyKnownTicketLabelCanBeParsedCaseInsensitively() {
        val labels = listOf(
            "PERUS 22 €",
            "alennus 13 €",
            "Teatteriala 10 €",
            "Jäsen 5 €",
            "Ryhmä perus 20 €",
            "Ryhmä alennus 12 €",
            "KAIKUKORTTI",
            "Vapaalippu"
        )

        val schema = SheetColumnSchema.fromOrderedHeaders(baseHeaders(*labels.toTypedArray(), "KORTTI"))

        assertEquals(8, schema.ticketTypes.size)
        assertEquals(TicketType.entries.map { it.name }.filterNot { it == TicketType.UNSPECIFIED.name }.toSet(), schema.ticketTypes.map { it.name }.toSet())
    }

    private fun alias(kind: SheetFieldClassification) = StoredSheetAlias(
        kind = kind,
        label = "",
        priceCents = null,
        allowsSplitPayment = true
    )

    private fun baseHeaders(vararg operational: String): List<String> = listOf(
        "Sukunimi",
        "Etunimi",
        "Yhteystiedot",
        "SAAPUNUT ESITYKSEEN"
    ) + operational + listOf("HUOM! jotain")
}
