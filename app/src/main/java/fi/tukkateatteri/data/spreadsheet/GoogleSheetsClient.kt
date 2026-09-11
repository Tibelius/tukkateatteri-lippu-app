package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toAbbreviatedId
import fi.tukkateatteri.logging.toLogSummary
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class GoogleSheetTab(
    val title: String,
    val rows: List<List<String>>
)

data class GoogleSheetImportCandidate(
    val sheetTitle: String,
    val performanceName: String,
    val date: String,
    val sortDate: LocalDate?
)

data class GoogleSheetImportData(
    val candidate: GoogleSheetImportCandidate,
    val rows: List<ReservationSpreadsheetRow>,
    val schema: SheetColumnSchema
)

enum class GoogleSheetLockFailure {
    HELD_BY_ANOTHER_DEVICE,
    ACQUISITION_LOST,
    RENEWAL_LOST
}

class GoogleSheetLockedException(
    val failure: GoogleSheetLockFailure,
    sheetTitle: String,
    holderDeviceId: String? = null
) : IllegalStateException(
    buildString {
        append("Google Sheets lock failed for tab '")
        append(sheetTitle)
        append("': ")
        append(failure.name)
        holderDeviceId?.takeIf(String::isNotBlank)?.let { append(" (holder: $it)") }
    }
)

class GoogleSheetsRequestException(
    method: String,
    operation: String,
    statusCode: Int? = null,
    cause: Throwable? = null
) : IOException(
    buildString {
        append("Google Sheets ")
        append(operation)
        append(" request failed")
        statusCode?.let { append(" with HTTP status $it") }
        append(" ($method).")
    },
    cause
)

data class ExportedSpreadsheetRow(
    val sourceIdentity: String,
    val sheetRowId: String
)

internal class GoogleSheetsClient(
    private val deviceId: String = "Android"
) {
    private val localPerformanceLocks = KeyedMutex()
    internal val aliasRegistryLocks = KeyedMutex()
    internal val cachedRemoteAliases = ConcurrentHashMap<String, MutableSet<String>>()
    internal val api = GoogleSheetsApiClient()

    private suspend fun loadTabs(
        spreadsheetUrl: String,
        accessToken: String
    ): List<GoogleSheetTab> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        AppLog.debug(LOG_COMPONENT) { "Starting spreadsheet tab discovery" }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val metadata = api.loadSpreadsheetMetadata(spreadsheetId, accessToken)
        val titles = buildList {
            val sheets = metadata.getJSONArray("sheets")
            for (index in 0 until sheets.length()) {
                add(sheets.getJSONObject(index).getJSONObject("properties").getString("title"))
            }
        }
        val rowsByTitle = api.loadValuesForTabs(spreadsheetId, titles, accessToken)
        return@withContext titles.map { title -> GoogleSheetTab(title, rowsByTitle.getValue(title)) }
            .also { tabs ->
                AppLog.info(LOG_COMPONENT) {
                    "Loaded ${tabs.size} spreadsheet tabs in ${AppLog.elapsedMillis(startedAt)} ms"
                }
                AppLog.debug(LOG_COMPONENT) {
                    "Received tab values: ${tabs.joinToString { tab -> "${tab.title}=${tab.rows.size} rows" }}"
                }
            }
    }

    suspend fun <T> withPerformanceLock(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val performanceKey = performanceLockKey(spreadsheetId, sheetTitle)
        AppLog.debug(LOG_COMPONENT) { "Waiting for local performance lock; tab=$sheetTitle" }
        localPerformanceLocks.withLock(performanceKey) {
            AppLog.debug(LOG_COMPONENT) { "Entered local performance lock; tab=$sheetTitle" }
            withRemotePerformanceLock(
                spreadsheetId = spreadsheetId,
                performanceKey = performanceKey,
                sheetTitle = sheetTitle,
                accessToken = accessToken,
                action = action
            )
        }
    }

    private suspend fun <T> withRemotePerformanceLock(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T {
        var lockId = UUID.randomUUID().toString()
        val now = Instant.now()
        AppLog.info(LOG_COMPONENT) { "Acquiring remote performance lock; tab=$sheetTitle" }
        var lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        if (removeExpiredLocks(spreadsheetId, lockTable, now, accessToken)) {
            AppLog.debug(LOG_COMPONENT) { "Removed expired lock rows before acquisition; tab=$sheetTitle" }
            lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        }
        val existingLock = lockTable.activeLockFor(performanceKey, now)
        if (existingLock != null) {
            if (existingLock.deviceId != deviceId) {
                AppLog.warning(LOG_COMPONENT) {
                    "Remote lock is held by another device; tab=$sheetTitle, holder=${existingLock.deviceId}"
                }
                throw GoogleSheetLockedException(
                    failure = GoogleSheetLockFailure.HELD_BY_ANOTHER_DEVICE,
                    sheetTitle = sheetTitle,
                    holderDeviceId = existingLock.deviceId
                )
            }
            lockId = existingLock.lockId.ifBlank { lockId }
            AppLog.debug(LOG_COMPONENT) {
                "Reusing this device's remote lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
            }
        }

        val expiresAt = now.plusSeconds(LOCK_DURATION_SECONDS)
        if (existingLock == null) {
            AppLog.debug(LOG_COMPONENT) {
                "Writing remote lock to an explicit row; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
            }
            api.updateCells(
                spreadsheetId = spreadsheetId,
                accessToken = accessToken,
                values = lockTable.valuesFor(
                    rowNumber = lockTable.firstAvailableRowNumber(),
                    performanceKey = performanceKey,
                    lockId = lockId,
                    lockedAt = now.toString(),
                    expiresAt = expiresAt.toString(),
                    deviceId = deviceId
                )
            )
        } else {
            AppLog.debug(LOG_COMPONENT) {
                "Refreshing existing remote lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
            }
            api.updateCells(
                spreadsheetId = spreadsheetId,
                accessToken = accessToken,
                values = lockTable.valuesFor(
                    rowNumber = existingLock.rowNumber,
                    performanceKey = performanceKey,
                    lockId = lockId,
                    lockedAt = now.toString(),
                    expiresAt = expiresAt.toString(),
                    deviceId = deviceId
                )
            )
        }
        val confirmedLock = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken)
            .toLockTable()
            .activeLockFor(performanceKey, Instant.now())
        if (confirmedLock?.lockId != lockId || confirmedLock.deviceId != deviceId) {
            AppLog.warning(LOG_COMPONENT) {
                "Remote lock acquisition could not be confirmed; tab=$sheetTitle, " +
                    "expected=${lockId.toAbbreviatedId()}, actual=${confirmedLock?.lockId?.toAbbreviatedId() ?: "none"}"
            }
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.ACQUISITION_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = confirmedLock?.deviceId
            )
        }
        AppLog.info(LOG_COMPONENT) {
            "Acquired remote performance lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
        }

        try {
            return coroutineScope {
                val renewalJob = launch {
                    while (isActive) {
                        delay(LOCK_RENEWAL_INTERVAL_MILLIS)
                        renewPerformanceLock(
                            spreadsheetId = spreadsheetId,
                            performanceKey = performanceKey,
                            sheetTitle = sheetTitle,
                            lockId = lockId,
                            accessToken = accessToken
                        )
                    }
                }
                try {
                    action()
                } finally {
                    renewalJob.cancelAndJoin()
                }
            }
        } finally {
            try {
                releasePerformanceLock(spreadsheetId, performanceKey, lockId, accessToken)
                AppLog.debug(LOG_COMPONENT) {
                    "Released remote performance lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                AppLog.warning(LOG_COMPONENT, exception) {
                    "Remote lock release failed and will be left to expire; tab=$sheetTitle, " +
                        "lockId=${lockId.toAbbreviatedId()}"
                }
            }
        }
    }

    suspend fun exportRows(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        rows: List<ReservationSpreadsheetRow>
    ): List<ExportedSpreadsheetRow> = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        AppLog.info(LOG_COMPONENT) { "Starting row export; tab=$sheetTitle, rows=${rows.size}" }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val existingRows = api.loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = existingRows.indexOfFirst { row -> row.any { cell -> cell.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = api.ensureApplicationHeaders(spreadsheetId, sheetTitle, existingRows, headerRowIndex, accessToken)
        api.requireApplicationHeaders(headers)
        val existingById = existingRows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            row.valueAt(headers[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace()
                .takeIf(String::isNotBlank)
                ?.let { it to headerRowIndex + index + 2 }
        }.toMap()
        val existingByName = existingRows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val key = row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity() to row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity()
            key.takeIf { it.first.isNotBlank() || it.second.isNotBlank() }?.let { it to headerRowIndex + index + 2 }
        }.toMap()
        val exportedRows = mutableListOf<ExportedSpreadsheetRow>()
        var nextAvailableRow = api.firstAvailableReservationRow(existingRows, headerRowIndex)
        rows.forEach { row ->
            val sheetRowId = row.sheetRowId.ifBlank { UUID.randomUUID().toString() }
            val rowNumber = existingById[sheetRowId]
                ?: row.sourceIdentity.toNameKeyOrNull()?.let(existingByName::get)
                ?: nextAvailableRow.also { nextAvailableRow += 1 }
            val isAddition = rowNumber !in existingById.values && rowNumber !in existingByName.values
            val shouldInsertRow = isAddition && (
                rowNumber > existingRows.size ||
                    existingRows.getOrNull(rowNumber - 1)?.any { cell ->
                        cell.normalizedHeader().startsWith(HEADER_RESERVATION_TOTAL)
                    } == true
                )
            if (shouldInsertRow) {
                AppLog.debug(LOG_COMPONENT) { "Inserting reservation row before summary; tab=$sheetTitle, row=$rowNumber" }
                api.insertReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            }
            api.updateCells(
                spreadsheetId = spreadsheetId,
                accessToken = accessToken,
                values = row.toSheetCellValues(
                    sheetTitle = sheetTitle,
                    rowNumber = rowNumber,
                    headers = headers,
                    sheetRowId = sheetRowId,
                    operation = if (isAddition) APP_OPERATION_ADD else null
                )
            )
            AppLog.debug(LOG_COMPONENT) {
                "Exported ${row.toLogSummary()}, targetRow=$rowNumber, addition=$isAddition, " +
                    "sheetRowId=${sheetRowId.toAbbreviatedId()}"
            }
            exportedRows += ExportedSpreadsheetRow(row.sourceIdentity, sheetRowId)
        }
        exportedRows.also {
            AppLog.info(LOG_COMPONENT) {
                "Completed row export; tab=$sheetTitle, rows=${it.size}, durationMs=${AppLog.elapsedMillis(startedAt)}"
            }
        }
    }

    suspend fun softDeleteRow(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        sheetRowId: String,
        sourceIdentity: String
    ) = withContext(Dispatchers.IO) {
        AppLog.info(LOG_COMPONENT) {
            "Starting soft deletion; tab=$sheetTitle, sheetRowId=${sheetRowId.toAbbreviatedId()}"
        }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val rows = api.loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.canonicalDataHeader() to index }
            .toMap()
        api.requireApplicationHeaders(headers)
        val rowNumber = rows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val matchesId = sheetRowId.isNotBlank() &&
                row.valueAt(headers[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace() == sheetRowId
            val matchesSourceIdentity = sourceIdentity.toNameKeyOrNull() == (
                row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity() to
                    row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity()
                )
            (matchesId || matchesSourceIdentity).takeIf { it }?.let { headerRowIndex + index + 2 }
        }.firstOrNull() ?: sourceIdentity.toLegacyDoorSaleDataRowIndexOrNull()
            ?.let { headerRowIndex + it + 2 }
            ?: return@withContext
        if (HARD_DELETE_FROM_SHEET) {
            api.deleteReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            return@withContext
        }
        val mutationId = UUID.randomUUID().toString()
        api.updateCells(
            spreadsheetId,
            accessToken,
            deletedRowCellValues(sheetTitle, rowNumber, headers, mutationId)
        )
        api.strikeThroughRow(spreadsheetId, sheetTitle, rowNumber, headers.values.maxOrNull() ?: 0, accessToken)
        AppLog.info(LOG_COMPONENT) { "Soft-deleted spreadsheet row; tab=$sheetTitle, row=$rowNumber" }
    }

    /** Removes app-only markers after a direct Sheet edit; reservation values remain untouched. */
    suspend fun clearApplicationMetadata(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        sheetRowId: String,
        sourceIdentity: String
    ) = withContext(Dispatchers.IO) {
        AppLog.debug(LOG_COMPONENT) {
            "Clearing app mutation metadata; tab=$sheetTitle, sheetRowId=${sheetRowId.toAbbreviatedId()}"
        }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val rows = api.loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.canonicalDataHeader() to index }
            .toMap()
        val rowNumber = rows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val matchesId = sheetRowId.isNotBlank() &&
                row.valueAt(headers[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace() == sheetRowId
            val matchesSourceIdentity = sourceIdentity.toNameKeyOrNull() == (
                row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity() to
                    row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity()
                )
            (matchesId || matchesSourceIdentity).takeIf { it }?.let { headerRowIndex + index + 2 }
        }.firstOrNull() ?: return@withContext
        val metadataCells = APPLICATION_MUTATION_METADATA_HEADERS.mapNotNull { header ->
            headers[header]?.let { columnIndex ->
                SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), "")
            }
        }
        if (metadataCells.isNotEmpty()) {
            api.updateCells(spreadsheetId, accessToken, metadataCells)
        }
        api.clearStrikeThroughRow(
            spreadsheetId,
            sheetTitle,
            rowNumber,
            headers.values.maxOrNull() ?: 0,
            accessToken
        )
        AppLog.debug(LOG_COMPONENT) { "Cleared app metadata and strikethrough; tab=$sheetTitle, row=$rowNumber" }
    }

    suspend fun clearManualRowStrikethrough(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        rowNumbers: Collection<Int>
    ) = withContext(Dispatchers.IO) {
        if (rowNumbers.isEmpty()) return@withContext
        AppLog.debug(LOG_COMPONENT) {
            "Clearing strikethrough from ${rowNumbers.size} manually managed rows; tab=$sheetTitle"
        }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.setRowsStrikethrough(
            spreadsheetId = spreadsheetId,
            sheetTitle = sheetTitle,
            rowNumbers = rowNumbers,
            lastColumnIndex = api.loadValues(
                spreadsheetId,
                sheetTitle,
                accessToken
            ).maxOfOrNull(List<String>::size)?.minus(1)?.coerceAtLeast(0) ?: 0,
            enabled = false,
            accessToken = accessToken
        )
    }

    suspend fun loadImportData(
        spreadsheetUrl: String,
        accessToken: String,
        localAliases: Map<String, StoredSheetAlias> = emptyMap()
    ): List<GoogleSheetImportData> = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val tabs = loadTabs(spreadsheetUrl, accessToken)
        val storedAliases = tabs.firstOrNull { it.title == APPLICATION_SHEET_TITLE }
            ?.storedAliases().orEmpty() + localAliases
        return@withContext tabs
            .mapNotNull { tab ->
                tab.toImportCandidateOrNull()?.let { candidate ->
                    parseImportData(tab, candidate, storedAliases)
                }
            }
            .sortedWith(
                compareBy<GoogleSheetImportData> { it.candidate.sortDate ?: LocalDate.MAX }
                    .thenBy { it.candidate.date }
            )
            .also { imported ->
                AppLog.info(LOG_COMPONENT) {
                    "Parsed importable spreadsheet data; performances=${imported.size}, rows=${imported.sumOf { it.rows.size }}"
                }
            }
    }

    suspend fun loadImportDataForTab(
        spreadsheetUrl: String,
        accessToken: String,
        sheetTitle: String,
        localAliases: Map<String, StoredSheetAlias> = emptyMap()
    ): GoogleSheetImportData = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        AppLog.debug(LOG_COMPONENT) { "Loading import data for tab=$sheetTitle" }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val values = api.loadValuesForTabs(
            spreadsheetId,
            listOf(sheetTitle, APPLICATION_SHEET_TITLE),
            accessToken
        )
        val tab = GoogleSheetTab(
            title = sheetTitle,
            rows = values.getValue(sheetTitle)
        )
        val storedAliases = GoogleSheetTab(
            title = APPLICATION_SHEET_TITLE,
            rows = values.getValue(APPLICATION_SHEET_TITLE)
        ).storedAliases() + localAliases
        val candidate = requireNotNull(tab.toImportCandidateOrNull()) {
            "Välilehdeltä puuttuu Esitys: tai Pvm: -tieto."
        }
        parseImportData(tab, candidate, storedAliases).also { data ->
            AppLog.info(LOG_COMPONENT) {
                "Loaded import data; tab=$sheetTitle, rows=${data.rows.size}, " +
                    "durationMs=${AppLog.elapsedMillis(startedAt)}"
            }
        }
    }

    private fun parseImportData(
        tab: GoogleSheetTab,
        candidate: GoogleSheetImportCandidate,
        storedAliases: Map<String, StoredSheetAlias>
    ): GoogleSheetImportData {
        val schema = tab.toColumnSchema(storedAliases)
        val rows = tab.toReservationSpreadsheetRows(candidate, schema)
        val invalidMetadataCount = rows.count {
            it.applicationMutationMetadataState == ApplicationMutationMetadataState.INVALID
        }
        AppLog.debug(LOG_COMPONENT) {
            "Parsed performance tab=${tab.title}; rows=${rows.size}, invalidMetadata=$invalidMetadataCount, " +
                "tickets=${schema.ticketTypes.joinToString { it.displayLabel }}, " +
                "payments=${schema.paymentMethods.joinToString { it.label }}"
        }
        if (invalidMetadataCount > 0) {
            AppLog.warning(LOG_COMPONENT) {
                "Found $invalidMetadataCount rows with incomplete or invalid app metadata; tab=${tab.title}"
            }
        }
        return GoogleSheetImportData(candidate, rows, schema)
    }

    private fun removeExpiredLocks(
        spreadsheetId: String,
        lockTable: LockTable,
        now: Instant,
        accessToken: String
    ): Boolean {
        val expiredLocks = lockTable.rows.filter { lock ->
            !lock.isActiveAt(now)
        }
        if (expiredLocks.isEmpty()) return false
        AppLog.info(LOG_COMPONENT) { "Removing ${expiredLocks.size} expired or invalid remote locks" }
        api.updateCells(
            spreadsheetId = spreadsheetId,
            accessToken = accessToken,
            values = expiredLocks.flatMap { lockTable.clearValuesFor(it.rowNumber) }
        )
        return true
    }

    private fun releasePerformanceLock(
        spreadsheetId: String,
        performanceKey: String,
        lockId: String,
        accessToken: String
    ) {
        val lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.ownedLock(performanceKey, lockId, deviceId) ?: run {
            AppLog.warning(LOG_COMPONENT) {
                "Skipped remote lock release because the owned lock row no longer exists; " +
                    "expected=${lockId.toAbbreviatedId()}"
            }
            return
        }
        api.updateCells(
            spreadsheetId,
            accessToken,
            lockTable.clearValuesFor(existingLock.rowNumber)
        )
    }

    private fun renewPerformanceLock(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        lockId: String,
        accessToken: String
    ) {
        val lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.ownedLock(performanceKey, lockId, deviceId)
        if (existingLock == null) {
            AppLog.warning(LOG_COMPONENT) {
                "Remote lock renewal lost ownership; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
            }
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.RENEWAL_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = lockTable.activeLockFor(performanceKey, Instant.now())?.deviceId
            )
        }
        val now = Instant.now()
        api.updateCells(
            spreadsheetId = spreadsheetId,
            accessToken = accessToken,
            values = lockTable.valuesFor(
                rowNumber = existingLock.rowNumber,
                performanceKey = performanceKey,
                lockId = lockId,
                lockedAt = now.toString(),
                expiresAt = now.plusSeconds(LOCK_DURATION_SECONDS).toString(),
                deviceId = deviceId
            )
        )
        AppLog.verbose(LOG_COMPONENT) {
            "Renewed remote performance lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
        }
    }

    private companion object {
        const val LOG_COMPONENT = "Sheets"
        const val LOCK_DURATION_SECONDS = 30L
        const val LOCK_RENEWAL_INTERVAL_MILLIS = 10_000L
        const val HARD_DELETE_FROM_SHEET = false
    }
}
