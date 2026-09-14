package fi.tukkateatteri.data.spreadsheet

import java.time.Instant

internal data class LockRow(
    val performanceKey: String,
    val rowNumber: Int,
    val lockId: String,
    val lockedAt: Instant?,
    val expiresAt: Instant?,
    val deviceId: String
) {
    fun isActiveAt(now: Instant): Boolean =
        lockId.isNotBlank() &&
            deviceId.isNotBlank() &&
            lockedAt != null &&
            expiresAt != null &&
            !now.isBefore(lockedAt) &&
            expiresAt.isAfter(now) &&
            expiresAt.isAfter(lockedAt)

    fun isOwnedBy(lockId: String, deviceId: String): Boolean =
        this.lockId == lockId && this.deviceId == deviceId
}

internal data class LockTable(
    val headers: Map<String, Int>,
    val rows: List<LockRow>,
    val firstDataRowNumber: Int
) {
    fun activeLockFor(performanceKey: String, now: Instant): LockRow? = rows.lastOrNull { lock ->
        lock.performanceKey == performanceKey && lock.isActiveAt(now)
    }

    fun ownedLock(performanceKey: String, lockId: String, deviceId: String): LockRow? =
        rows.lastOrNull { lock ->
            lock.performanceKey == performanceKey && lock.isOwnedBy(lockId, deviceId)
        }

    fun firstAvailableRowNumber(): Int {
        val occupiedRows = rows.mapTo(mutableSetOf(), LockRow::rowNumber)
        return generateSequence(firstDataRowNumber) { it + 1 }.first { it !in occupiedRows }
    }

    fun valuesFor(
        rowNumber: Int,
        performanceKey: String,
        lockId: String,
        lockedAt: String,
        expiresAt: String,
        deviceId: String = ""
    ): List<SheetCellValue> = buildList {
        fun set(header: String, value: Any) {
            headers[header]?.let { columnIndex ->
                add(SheetCellValue(sheetCellRange(LOCK_SHEET_TITLE, columnIndex, rowNumber), value))
            }
        }
        set(LOCK_HEADER_PERFORMANCE_ID, performanceKey)
        set(LOCK_HEADER_UUID, lockId)
        set(LOCK_HEADER_LOCKED_AT, lockedAt)
        set(LOCK_HEADER_EXPIRES_AT, expiresAt)
        set(LOCK_HEADER_DEVICE_LABEL, deviceId)
    }

    fun clearValuesFor(rowNumber: Int): List<SheetCellValue> = REQUIRED_LOCK_HEADERS.mapNotNull { header ->
        headers[header]?.let { columnIndex ->
            SheetCellValue(sheetCellRange(LOCK_SHEET_TITLE, columnIndex, rowNumber), "")
        }
    }
}

internal fun List<List<String>>.toLockTable(): LockTable {
    val headerRowIndex = indexOfFirst { row -> row.any { it.normalizedHeader() == LOCK_HEADER_PERFORMANCE_ID } }
    require(headerRowIndex >= 0) { "Sovellus-välilehdeltä puuttuu performance_id-sarake." }
    val headers = get(headerRowIndex).mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    require(REQUIRED_LOCK_HEADERS.all(headers::containsKey)) {
        "Sovellus-välilehden lukitusotsikot eivät vastaa sovittua muotoa."
    }
    val rows = drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
        val performanceKey = row.valueAt(headers[LOCK_HEADER_PERFORMANCE_ID]).trimSpreadsheetWhitespace()
        performanceKey.takeIf(String::isNotBlank)?.let {
            LockRow(
                performanceKey = performanceKey,
                rowNumber = headerRowIndex + index + 2,
                lockId = row.valueAt(headers[LOCK_HEADER_UUID]).trimSpreadsheetWhitespace(),
                lockedAt = row.valueAt(headers[LOCK_HEADER_LOCKED_AT]).toInstantOrNull(),
                expiresAt = row.valueAt(headers[LOCK_HEADER_EXPIRES_AT]).toInstantOrNull(),
                deviceId = row.valueAt(headers[LOCK_HEADER_DEVICE_LABEL]).trimSpreadsheetWhitespace()
            )
        }
    }
    return LockTable(
        headers = headers,
        rows = rows,
        firstDataRowNumber = headerRowIndex + 2
    )
}

private fun String.toInstantOrNull(): Instant? = runCatching {
    Instant.parse(trimSpreadsheetWhitespace())
}.getOrNull()
