package fi.tukkateatteri.data.spreadsheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleSheetFormatTest {
    @Test
    fun currentSourceIdentityReturnsCustomerNameInsteadOfContact() {
        assertEquals(
            SourceCustomerIdentity("kippari", "kalle", "kalle@example.com"),
            "show|date|kippari|kalle|kalle@example.com".toCustomerIdentityOrNull()
        )
    }

    @Test
    fun legacySourceIdentityStillReturnsCustomerName() {
        assertEquals(
            SourceCustomerIdentity("kippari", "kalle", null),
            "show|date|kippari|kalle".toCustomerIdentityOrNull()
        )
    }

    @Test
    fun doorSaleIdentityDoesNotRepresentACustomerName() {
        assertNull("show|date|ovelta|3".toCustomerIdentityOrNull())
    }
}
