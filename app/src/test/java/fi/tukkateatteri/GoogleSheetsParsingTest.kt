package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.ApplicationRowState
import fi.tukkateatteri.data.spreadsheet.ApplicationMutationMetadataState
import fi.tukkateatteri.data.spreadsheet.GoogleSheetTab
import fi.tukkateatteri.data.spreadsheet.sheetContentHash
import fi.tukkateatteri.data.spreadsheet.toImportCandidateOrNull
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRows
import fi.tukkateatteri.data.spreadsheet.toSheetCellValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class GoogleSheetsParsingTest {
    @Test
    fun checkboxLayoutWithoutReservedCountImportsAndExportsBooleanMarkers() {
        val checkboxHeaders = headers.filterNot { it.startsWith("Varatut liput") }
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                checkboxHeaders,
                MutableList(checkboxHeaders.size) { "" }.apply {
                    this[0] = "Testaaja"
                    this[1] = "Tiina"
                    this[3] = "TRUE"
                    this[4] = "TRUE"
                    this[12] = "TRUE"
                },
                List(checkboxHeaders.size) { "" },
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )

        val row = tab.toReservationSpreadsheetRows(requireNotNull(tab.toImportCandidateOrNull())).single()
        val indexes = checkboxHeaders.mapIndexed { index, header -> header.lowercase() to index }.toMap()
        val exported = row.toSheetCellValues("24.10", 2, indexes)

        assertEquals(1, row.reservedSeatCount)
        assertEquals(1, row.arrivalCount)
        assertEquals(TicketType.BASIC, row.realizedTickets.single().ticketType)
        assertTrue(exported.any { it.value == true })
    }

    @Test
    fun importCandidate_readsPerformanceAndDateFromMetadataBelowTheTable() {
        val candidate = testTab().toImportCandidateOrNull()

        requireNotNull(candidate)
        assertEquals("24.10", candidate.sheetTitle)
        assertEquals("Yön Vuodenaika", candidate.performanceName)
        assertEquals("24.10.2026", candidate.date)
        assertEquals(LocalDate.of(2026, 10, 24), candidate.sortDate)
    }

    @Test
    fun importCandidate_returnsNullWhenEitherMetadataValueIsMissing() {
        assertNull(testTab(includeDate = false).toImportCandidateOrNull())
        assertNull(testTab(includePerformance = false).toImportCandidateOrNull())
    }

    @Test
    fun importRows_readsTicketTypesPaymentsArrivalsAndNotes() {
        val tab = testTab()
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        val rows = tab.toReservationSpreadsheetRows(candidate)

        assertEquals(3, rows.size)
        assertEquals("Kippari", rows[0].lastName)
        assertEquals("Kalle", rows[0].firstName)
        assertEquals("kippari@gmail.com", rows[0].contact)
        assertEquals(1, rows[0].reservedSeatCount)
        assertEquals(1, rows[0].reservedTicketCounts[TicketType.BASIC])
        assertEquals(PaymentMethod.LIPPUAGENTTI, rows[0].realizedTickets.single().payments.single().method)
        assertEquals("Ennakkoon ostettu", rows[0].notes)
        assertEquals(2, rows[0].sourceRowNumber)
        assertEquals(ApplicationMutationMetadataState.NONE, rows[0].applicationMutationMetadataState)

        assertEquals(1, rows[1].reservedSeatCount)
        assertEquals(1, rows[1].arrivalCount)
        assertEquals(1, rows[1].reservedTicketCounts[TicketType.DISCOUNT])
        assertEquals(PaymentMethod.CARD, rows[1].realizedTickets.single().payments.single().method)

        assertEquals(1, rows[2].reservedSeatCount)
        assertEquals(1, rows[2].reservedTicketCounts[TicketType.THEATRE_INDUSTRY])
        assertEquals(PaymentMethod.CASH, rows[2].realizedTickets.single().payments.single().method)
    }

    @Test
    fun importRows_stopsAtTheFirstBlankReservationRowAndIgnoresSummaryRows() {
        val tab = testTab(
            extraRows = listOf(
                emptyRow(),
                dataRow(0 to "Yhteensä", 3 to "7", 5 to "7"),
                dataRow(0 to "Ei", 1 to "tuoda", 3 to "1")
            )
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        val rows = tab.toReservationSpreadsheetRows(candidate)

        assertEquals(listOf("Kippari", "Meikäläinen", "Jorma"), rows.map { it.lastName })
    }

    @Test
    fun importRowsRetainsAppOwnedBlankRowForDoorSaleRecovery() {
        val rowId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
        val tabWithoutState = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(0 to "Ovimyynti", 3 to "1", 4 to "1", 5 to "1", 13 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            ),
            sheetId = 42,
            rowIdsByRowNumber = mapOf(2 to rowId)
        )
        val candidate = requireNotNull(tabWithoutState.toImportCandidateOrNull())
        val original = tabWithoutState.toReservationSpreadsheetRows(candidate).single()
        val tab = tabWithoutState.copy(
            applicationRowStates = mapOf(
                rowId to validState(rowId, original.sheetContentHash(), "Lisäys")
            )
        )

        val recovered = tab.toReservationSpreadsheetRows(candidate).single()

        assertTrue(recovered.isDoorSale)
        assertFalse(recovered.isMalformedAppOwnedDoorSaleRow)
        assertEquals(ApplicationMutationMetadataState.VALID, recovered.applicationMutationMetadataState)
    }

    @Test
    fun importRows_acceptsCountMarkersUsedInSpreadsheets() {
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(
                    0 to "Merkkinen",
                    1 to "Meri",
                    3 to "3",
                    5 to "x",
                    11 to "✓",
                    12 to "k",
                    13 to "x"
                ),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        val row = tab.toReservationSpreadsheetRows(candidate).single()

        assertEquals(1, row.reservedTicketCounts[TicketType.BASIC])
        assertEquals(1, row.reservedTicketCounts[TicketType.KAIKUKORTTI])
        assertEquals(1, row.reservedTicketCounts[TicketType.FREE_TICKET])
        assertEquals(1, row.paymentTicketCounts[PaymentMethod.CARD])
    }

    @Test
    fun importRows_normalizesHeaderCapitalizationAndWhitespace() {
        val normalizedHeaders = headers.map { value ->
            "\u00A0 ${value.uppercase().replace(" ", "  \n\t")} \u00A0"
        }
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                normalizedHeaders,
                dataRow(0 to "Testaaja", 1 to "Tiina", 3 to "1", 4 to "1", 5 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        val row = tab.toReservationSpreadsheetRows(candidate).single()

        assertEquals("Testaaja", row.lastName)
        assertEquals(1, row.arrivalCount)
        assertEquals(1, row.reservedTicketCounts[TicketType.BASIC])
    }

    @Test
    fun importCandidate_trimsSpreadsheetWhitespaceFromLabelsAndValues() {
        val tab = GoogleSheetTab(
            title = "24.10. ",
            rows = listOf(
                headers,
                emptyRow(),
                listOf("\u00A0 ESITYS: \t", "\u00A0Yön Vuodenaika\u00A0"),
                listOf("  PVM:\n", "\t24.10.2026 ")
            )
        )

        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        assertEquals("Yön Vuodenaika", candidate.performanceName)
        assertEquals("24.10.2026", candidate.date)
        assertEquals("24.10. ", candidate.sheetTitle)
    }

    @Test
    fun importRows_doesNotTreatARealizedChildRowAsAReservation() {
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(0 to "Oletus", 1 to "Olivia", 5 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        assertEquals(1, tab.toReservationSpreadsheetRows(candidate).single().reservedSeatCount)
    }

    @Test
    fun importRows_returnsEmptyWhenTheReservationHeaderIsAbsent() {
        val tab = GoogleSheetTab(
            title = "yhteenveto",
            rows = listOf(
                listOf("Tapahtuma", "Yhteensä"),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull())

        assertTrue(tab.toReservationSpreadsheetRows(candidate).isEmpty())
    }

    @Test
    fun sourceIdentity_usesContactToDisambiguatePeopleWithTheSameName() {
        val original = testTab()
        val modified = testTab(
            firstRowOverrides = mapOf(2 to "uusi@example.com", 17 to "uusi huomautus")
        )
        val originalCandidate = requireNotNull(original.toImportCandidateOrNull())
        val modifiedCandidate = requireNotNull(modified.toImportCandidateOrNull())

        val originalRow = original.toReservationSpreadsheetRows(originalCandidate).first()
        val modifiedRow = modified.toReservationSpreadsheetRows(modifiedCandidate).first()

        assertFalse(originalRow.sourceIdentity == modifiedRow.sourceIdentity)
        assertFalse(originalRow.contact == modifiedRow.contact)
    }

    @Test
    fun importRows_usesInvisibleRowIdAndSkipsSoftDeletedRows() {
        val activeId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
        val deletedId = "4975807c-18a8-40af-b217-e5e856e65bf4"
        val tabWithoutStates = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(0 to "Kippari", 1 to "Kalle", 3 to "1"),
                dataRow(0 to "Poistettu", 1 to "Paavo", 3 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            ),
            sheetId = 42,
            rowIdsByRowNumber = mapOf(2 to activeId, 3 to deletedId)
        )
        val parsed = tabWithoutStates.toReservationSpreadsheetRows(
            requireNotNull(tabWithoutStates.toImportCandidateOrNull())
        )
        val tab = tabWithoutStates.copy(
            applicationRowStates = mapOf(
                activeId to validState(activeId, parsed[0].sheetContentHash(), "Muutos"),
                deletedId to validState(deletedId, parsed[1].sheetContentHash(), "Poisto")
            )
        )

        val rows = tab.toReservationSpreadsheetRows(requireNotNull(tab.toImportCandidateOrNull()))

        assertEquals(1, rows.size)
        assertEquals("e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe", rows.single().sheetRowId)
        assertEquals("sheet:e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe", rows.single().sourceIdentity)
        assertEquals(ApplicationMutationMetadataState.VALID, rows.single().applicationMutationMetadataState)
    }

    @Test
    fun importRows_treatsInvalidCentralStateAsAManualEdit() {
        val rowId = "e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe"
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(0 to "Korjattu", 1 to "Kaisa", 3 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            ),
            sheetId = 42,
            rowIdsByRowNumber = mapOf(2 to rowId),
            applicationRowStates = mapOf(rowId to validState(rowId, "0".repeat(64), "Muutos"))
        )

        val row = tab.toReservationSpreadsheetRows(requireNotNull(tab.toImportCandidateOrNull())).single()

        assertEquals("Korjattu", row.lastName)
        assertEquals(ApplicationMutationMetadataState.INVALID, row.applicationMutationMetadataState)
        assertEquals(
            "sheet:$rowId",
            row.sourceIdentity
        )
    }

    @Test
    fun importRows_groupsRealizedSeatRowsAndParsesFinnishPartialPayments() {
        val tab = GoogleSheetTab(
            title = "24.10",
            rows = listOf(
                headers,
                dataRow(0 to "Virtanen", 1 to "Maija", 2 to "maija@example.fi", 3 to "2", 5 to "2"),
                dataRow(
                    0 to "Virtanen",
                    1 to "Maija",
                    2 to "maija@example.fi",
                    4 to "1",
                    5 to "1",
                    17 to "Osamaksu: Kortti 12,00 €; Käteinen 10,00 €"
                ),
                dataRow(0 to "Virtanen", 1 to "Maija", 2 to "maija@example.fi", 4 to "1", 5 to "1", 13 to "1"),
                emptyRow(),
                listOf("Esitys:", "Yön Vuodenaika"),
                listOf("Pvm:", "24.10.2026")
            )
        )

        val reservation = tab.toReservationSpreadsheetRows(
            requireNotNull(tab.toImportCandidateOrNull())
        ).single()

        assertEquals(2, reservation.realizedTickets.size)
        assertEquals(listOf(1_200, 1_000), reservation.realizedTickets.first().payments.map { it.amountCents })
        assertEquals(PaymentMethod.CARD, reservation.realizedTickets.last().payments.single().method)
    }

    private fun validState(rowId: String, contentHash: String, operation: String) = ApplicationRowState(
        sheetId = 42,
        rowId = rowId,
        contentHash = contentHash,
        modifiedAt = Instant.parse("2026-09-09T12:00:00Z"),
        operation = operation,
        mutationId = "4e97744e-ef31-4d40-84d7-28e821af23a9",
        rowNumber = 2
    )

    private fun testTab(
        includePerformance: Boolean = true,
        includeDate: Boolean = true,
        extraRows: List<List<String>> = emptyList(),
        firstRowOverrides: Map<Int, String> = emptyMap()
    ): GoogleSheetTab {
        val firstRow = dataRow(
            0 to "Kippari",
            1 to "Kalle",
            2 to "kippari@gmail.com",
            3 to "1",
            5 to "1",
            16 to "1",
            17 to "Ennakkoon ostettu"
        ).toMutableList()
        firstRowOverrides.forEach { (index, value) -> firstRow[index] = value }
        return GoogleSheetTab(
            title = "24.10",
            rows = buildList {
                add(headers)
                add(firstRow)
                add(dataRow(0 to "Meikäläinen", 1 to "Matti", 2 to "040 123 1234", 3 to "4", 4 to "4", 6 to "4", 13 to "4"))
                add(dataRow(0 to "Jorma", 1 to "Seppo", 2 to "seppo@seppo.com", 3 to "2", 7 to "2", 14 to "2"))
                addAll(extraRows)
                if (extraRows.isEmpty()) add(emptyRow())
                if (includePerformance) add(listOf("Esitys:", "Yön Vuodenaika"))
                if (includeDate) add(listOf("Pvm:", "24.10.2026"))
            }
        )
    }

    private fun dataRow(vararg values: Pair<Int, String>): List<String> = MutableList(headers.size) { "" }.also { row ->
        values.forEach { (index, value) -> row[index] = value }
    }

    private fun emptyRow(): List<String> = List(headers.size) { "" }

    private companion object {
        val headers = listOf(
            "Sukunimi",
            "Etunimi",
            "Yhteystiedot",
            "Varatut liput kpl",
            "SAAPUNUT ESITYKSEEN eli lunastettujen lippujen lukumäärä",
            "Perus 22 €",
            "Alennus 13 €",
            "Teatteriala 10 €",
            "Jäsen 5 €",
            "Ryhmä perus 20 €",
            "Ryhmä alennus 12 €",
            "Kaikukortti",
            "Vapaalippu",
            "KORTTI",
            "KÄTEINEN",
            "EPASSI",
            "LIPPUAGENTTI",
            "HUOM! (Merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, Kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
        )
    }
}
