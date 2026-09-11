package fi.tukkateatteri.data.spreadsheet

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockTableTest {
    private val now: Instant = Instant.parse("2026-09-10T15:00:00Z")
    private val headers = listOf(
        "alias",
        "normalized_alias",
        "field_type",
        "display_name",
        "price_cents",
        "options",
        "",
        LOCK_HEADER_PERFORMANCE_ID,
        LOCK_HEADER_UUID,
        LOCK_HEADER_LOCKED_AT,
        LOCK_HEADER_EXPIRES_AT,
        LOCK_HEADER_DEVICE_LABEL
    )

    @Test
    fun lockRowsUseTheFirstFreeRowInTheDedicatedLockColumns() {
        val table = listOf(
            headers,
            lockRow("sheet|24.10.", "first", now.minusSeconds(5), now.plusSeconds(25)),
            emptyList(),
            lockRow("sheet|27.10.", "second", now.minusSeconds(5), now.plusSeconds(25))
        ).toLockTable()

        assertEquals(3, table.firstAvailableRowNumber())
        assertEquals(
            listOf("'Sovellus'!H3", "'Sovellus'!I3", "'Sovellus'!J3", "'Sovellus'!K3", "'Sovellus'!L3"),
            table.valuesFor(3, "sheet|30.10.", "new", now.toString(), now.plusSeconds(30).toString(), "device")
                .map(SheetCellValue::range)
        )
    }

    @Test
    fun incompleteAndInvalidTimeRangesAreNotActive() {
        val missingTimestamp = listOf(
            headers,
            lockRow("sheet|30.10.", "missing-time", null, now.plusSeconds(20))
        ).toLockTable()
        val reversedLease = listOf(
            headers,
            lockRow("sheet|30.10.", "reversed", now.plusSeconds(30), now.plusSeconds(20))
        ).toLockTable()

        assertNull(missingTimestamp.activeLockFor("sheet|30.10.", now))
        assertNull(reversedLease.activeLockFor("sheet|30.10.", now))
    }

    @Test
    fun ownershipLookupFindsTheExactRowInsteadOfAnUnrelatedDuplicate() {
        val table = listOf(
            headers,
            lockRow("sheet|30.10.", "ours", now, now.plusSeconds(30), "our-device"),
            lockRow("sheet|30.10.", "other", now, now.plusSeconds(30), "other-device")
        ).toLockTable()

        assertEquals(2, table.ownedLock("sheet|30.10.", "ours", "our-device")?.rowNumber)
    }

    private fun lockRow(
        key: String,
        id: String,
        lockedAt: Instant?,
        expiresAt: Instant?,
        device: String = "device"
    ): List<String> = listOf(
        "", "", "", "", "", "", "",
        key,
        id,
        lockedAt?.toString().orEmpty(),
        expiresAt?.toString().orEmpty(),
        device
    )
}
