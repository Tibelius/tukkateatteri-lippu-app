package fi.tukkateatteri.data.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceLockKeyTest {
    @Test
    fun surroundingWhitespaceInSheetTitleDoesNotChangeLockIdentity() {
        val expected = performanceLockKey("spreadsheet-id", "4.12.")

        val actual = performanceLockKey("spreadsheet-id", "\u00A0 4.12. \t")

        assertEquals(expected, actual)
    }
}
