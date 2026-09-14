package fi.tukkateatteri.data.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSheetFormatEdgeCasesTest {
    @Test
    fun columnIndexesUseSpreadsheetLettering() {
        val cases = mapOf(
            0 to "A",
            25 to "Z",
            26 to "AA",
            27 to "AB",
            51 to "AZ",
            52 to "BA",
            701 to "ZZ",
            702 to "AAA"
        )

        cases.forEach { (index, expected) -> assertEquals(expected, index.toColumnName()) }
    }

    @Test
    fun sheetNamesAndRangesEscapeApostrophes() {
        assertEquals("'Kesä''ilta'", "Kesä'ilta".toQuotedSheetName())
        assertEquals("'Kesä''ilta'!AB12", sheetCellRange("Kesä'ilta", 27, 12))
    }

    @Test
    fun spreadsheetIdIsReadFromCommonGoogleUrls() {
        val id = "1Yz1RcoJXp4xVrT77ozkj3_v6dPb4cFBgXtyZ9KX1Ln8"

        assertEquals(id, "https://docs.google.com/spreadsheets/d/$id/edit?usp=sharing".toSpreadsheetId())
        assertEquals(id, "https://docs.google.com/spreadsheets/d/$id/".toSpreadsheetId())
        assertThrows(IllegalArgumentException::class.java) { "https://example.com/$id".toSpreadsheetId() }
        assertThrows(IllegalArgumentException::class.java) { "".toSpreadsheetId() }
    }

    @Test
    fun headerNormalizationHandlesFormattingNoiseAndCanonicalHeaders() {
        assertEquals("varatut liput kpl", " \u00a0VARATUT   LIPUT\n*KPL* ".normalizedHeader())
        assertEquals(HEADER_ARRIVAL_COUNT, " Saapunut ".canonicalDataHeader())
        assertEquals(HEADER_NOTES, "HUOM! jotain muuta".canonicalDataHeader())
        assertEquals(HEADER_PERFORMANCE, "Esitys".canonicalDataHeader())
        assertEquals(HEADER_DATE, "PVM".canonicalDataHeader())
        assertEquals("kortti", "KORTTI".canonicalDataHeader())
    }

    @Test
    fun checkboxValuesTreatOnlyBlankFalseAndZeroAsUnchecked() {
        listOf("", " ", "FALSE", " false ", "0", "\u00a00\u00a0").forEach { value ->
            assertEquals("Expected '$value' to be unchecked", 0, value.toTicketCount())
        }
        listOf("TRUE", "x", "✓", "1", "2", "kyllä").forEach { value ->
            assertEquals("Expected '$value' to be checked", 1, value.toTicketCount())
        }
    }

    @Test
    fun sourceCustomerIdentityIncludesOptionalContact() {
        val withContact = "show|date|Virtanen|Maija|maija@example.fi".toCustomerIdentityOrNull()
        val withoutContact = "show|date|Virtanen|Maija".toCustomerIdentityOrNull()

        assertTrue(requireNotNull(withContact).matches("Virtanen", "Maija", "maija@example.fi"))
        assertFalse(withContact.matches("Virtanen", "Maija", "other@example.fi"))
        assertTrue(requireNotNull(withoutContact).matches("Virtanen", "Maija", "anything"))
        assertFalse(withoutContact.matches("virtanen", "Maija", "anything"))
    }

    @Test
    fun malformedAndDoorSaleIdentitiesAreNotCustomerIdentities() {
        assertNull("too|short|name".toCustomerIdentityOrNull())
        assertNull("show|date|ovelta|2".toCustomerIdentityOrNull())
        assertNull("".toCustomerIdentityOrNull())
    }

    @Test
    fun legacyDoorSaleIdentityRequiresExactShapeAndNumericRow() {
        assertEquals(15, "show|date|ovelta|15".toLegacyDoorSaleDataRowIndexOrNull())
        assertNull("show|date|ovelta|not-a-number".toLegacyDoorSaleDataRowIndexOrNull())
        assertNull("show|date|customer|15".toLegacyDoorSaleDataRowIndexOrNull())
        assertNull("show|date|ovelta|15|extra".toLegacyDoorSaleDataRowIndexOrNull())
    }

    @Test
    fun lockKeysTrimSheetEdgesButPreserveSpreadsheetAndTabIdentity() {
        assertEquals("sheet-id|24.10.", performanceLockKey("sheet-id", " 24.10. \u00a0"))
        assertFalse(performanceLockKey("sheet-a", "24.10") == performanceLockKey("sheet-b", "24.10"))
        assertFalse(performanceLockKey("sheet-a", "24.10") == performanceLockKey("sheet-a", "27.10"))
    }

    @Test
    fun summaryLabelsRecognizeSupportedFinnishFormsOnly() {
        assertTrue("Varaukset yhteensä: 12".isReservationSummaryLabel())
        assertTrue("VARAUKSIA: 12".isReservationSummaryLabel())
        assertFalse("Varauksen yhteystiedot".isReservationSummaryLabel())
        assertFalse("Yhteensä".isReservationSummaryLabel())
    }

    @Test
    fun apiUrlsAreMappedToReadableOperationNames() {
        assertEquals("spreadsheet batch update", "https://sheets.googleapis.com/v4/x:batchUpdate".toApiOperation())
        assertEquals("row append", "https://sheets.googleapis.com/v4/x:append".toApiOperation())
        assertEquals("value batch read", "values:batchGet".toApiOperation())
        assertEquals("value batch update", "values:batchUpdate".toApiOperation())
        assertEquals("value read", "/values/A1".toApiOperation())
        assertEquals("spreadsheet metadata read", "/spreadsheets/id".toApiOperation())
    }

    @Test
    fun uuidValidationRejectsNonUuidValues() {
        assertTrue("e0d9f1c9-464f-4bc8-b4aa-c784957ca3fe".isUuid())
        assertFalse("".isUuid())
        assertFalse("e0d9f1c9-464f-4bc8-b4aa".isUuid())
        assertFalse("not-a-uuid".isUuid())
    }
}
