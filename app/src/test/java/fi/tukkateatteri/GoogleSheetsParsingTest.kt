package fi.tukkateatteri

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.GoogleSheetTab
import fi.tukkateatteri.data.spreadsheet.toImportCandidateOrNull
import fi.tukkateatteri.data.spreadsheet.toReservationSpreadsheetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoogleSheetsParsingTest {
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
        assertEquals(1, rows[0].paymentTicketCounts[PaymentMethod.LIPPUAGENTTI])
        assertEquals("Ennakkoon ostettu", rows[0].notes)

        assertEquals(4, rows[1].reservedSeatCount)
        assertEquals(4, rows[1].arrivalCount)
        assertEquals(4, rows[1].reservedTicketCounts[TicketType.DISCOUNT])
        assertEquals(4, rows[1].paymentTicketCounts[PaymentMethod.CARD])

        assertEquals(2, rows[2].reservedSeatCount)
        assertEquals(2, rows[2].reservedTicketCounts[TicketType.THEATRE_INDUSTRY])
        assertEquals(2, rows[2].paymentTicketCounts[PaymentMethod.CASH])
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
        val normalizedHeaders = headers.mapIndexed { index, value ->
            when (index) {
                0 -> "  SUKUNIMI "
                3 -> "Varatut   liput  kpl"
                4 -> "Saapunut esitykseen eli lunastettujen lippujen lukumäärä"
                5 -> "PERUS 22 €"
                17 -> "HUOM! (Merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, Kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
                else -> value
            }
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
    fun importRows_usesOneSeatWhenTheReservedCountIsMissing() {
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
    fun sourceIdentity_isStableAcrossContactAndNoteChanges() {
        val original = testTab()
        val modified = testTab(
            firstRowOverrides = mapOf(2 to "uusi@example.com", 17 to "uusi huomautus")
        )
        val originalCandidate = requireNotNull(original.toImportCandidateOrNull())
        val modifiedCandidate = requireNotNull(modified.toImportCandidateOrNull())

        val originalRow = original.toReservationSpreadsheetRows(originalCandidate).first()
        val modifiedRow = modified.toReservationSpreadsheetRows(modifiedCandidate).first()

        assertEquals(originalRow.sourceIdentity, modifiedRow.sourceIdentity)
        assertFalse(originalRow.contact == modifiedRow.contact)
    }

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
