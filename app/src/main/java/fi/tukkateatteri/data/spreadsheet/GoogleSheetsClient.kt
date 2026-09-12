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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class GoogleSheetsClient(
    private val deviceId: String = "Android"
) {
    private val localPerformanceLocks = KeyedMutex()
    internal val aliasRegistryLocks = KeyedMutex()
    internal val cachedRemoteAliases = ConcurrentHashMap<String, MutableSet<String>>()
    internal val api = GoogleSheetsApiClient()
    private val importer = GoogleSheetImporter(api)

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
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val structure = api.loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
        val sheet = requireNotNull(structure.sheetsByTitle[sheetTitle]) {
            "Välilehteä '$sheetTitle' ei enää ole."
        }
        val values = api.loadValuesForTabs(
            spreadsheetId,
            listOf(sheetTitle, APPLICATION_SHEET_TITLE),
            accessToken
        )
        val existingRows = values.getValue(sheetTitle)
        val headerRowIndex = existingRows.indexOfFirst { row -> row.any { cell -> cell.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = existingRows[headerRowIndex]
            .mapIndexed { index, header -> header.canonicalDataHeader() to index }
            .toMap()
        val rowIdsByRowNumber = structure.rowIdsBySheetId[sheet.id].orEmpty()
        val existingById = rowIdsByRowNumber.entries
            .groupBy({ it.value }, { it.key })
            .mapValues { (_, rowNumbers) -> rowNumbers.min() }
        val existingByName = existingRows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val key = Triple(
                row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity(),
                row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity(),
                row.valueAt(headers[HEADER_CONTACT]).normalizedIdentity()
            )
            key.takeIf {
                it.first.isNotBlank() || it.second.isNotBlank()
            }?.let { it to headerRowIndex + index + 2 }
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, rowNumbers) -> rowNumbers.min() }
        val stateTable = values.getValue(APPLICATION_SHEET_TITLE).toApplicationRowStateTable()
        val stateRowsById = stateTable.rows
            .filter { state -> state.sheetId == sheet.id }
            .associateBy(ApplicationRowState::rowId)
        val exportedRows = mutableListOf<ExportedSpreadsheetRow>()
        val obsoleteChildRows = mutableSetOf<Int>()
        val summaryRowNumber = existingRows.indexOfFirst { cells ->
            cells.any(String::isReservationSummaryLabel)
        }.takeIf { it >= 0 }?.plus(1)
        var nextAvailableRow = api.firstAvailableReservationRow(existingRows, headerRowIndex)
        rows.forEach { row ->
            val physicalRows = row.toPhysicalSheetRows()
            val primaryRow = physicalRows.first()
            val idMatchedRowNumber = row.sheetRowId.takeIf(String::isNotBlank)?.let(existingById::get)
            val nameMatchedRowNumber = if (row.isDoorSale) {
                null
            } else {
                Triple(
                    row.lastName.normalizedIdentity(),
                    row.firstName.normalizedIdentity(),
                    row.contact.normalizedIdentity()
                ).takeIf { it.first.isNotBlank() || it.second.isNotBlank() }?.let(existingByName::get)
            }
            val rowNumber = idMatchedRowNumber
                ?: nameMatchedRowNumber
                ?: nextAvailableRow.also { nextAvailableRow += 1 }
            val sheetRowId = rowIdsByRowNumber[rowNumber]
                ?: row.sheetRowId.takeIf(String::isNotBlank)
                ?: UUID.randomUUID().toString()
            val isAddition = rowNumber !in existingById.values && rowNumber !in existingByName.values
            val shouldInsertRow = isAddition && (
                rowNumber > existingRows.size ||
                    summaryRowNumber?.let { rowNumber >= it } == true
                )
            if (shouldInsertRow) {
                AppLog.debug(LOG_COMPONENT) { "Inserting reservation row before summary; tab=$sheetTitle, row=$rowNumber" }
                api.insertReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            }
            if (rowNumber !in rowIdsByRowNumber) {
                api.attachRowIdentity(spreadsheetId, sheet.id, rowNumber, sheetRowId, accessToken)
            }
            val exportedRow = row.copy(sheetRowId = sheetRowId)
            val contentHash = exportedRow.sheetContentHash()
            val operation = if (isAddition) APP_OPERATION_ADD else APP_OPERATION_UPDATE
            api.updateCells(
                spreadsheetId = spreadsheetId,
                accessToken = accessToken,
                values = primaryRow.copy(sheetRowId = sheetRowId).toSheetCellValues(
                    sheetTitle = sheetTitle,
                    rowNumber = rowNumber,
                    headers = headers
                )
            )
            val existingState = stateRowsById[sheetRowId]
            if (existingState == null) {
                api.appendApplicationRowState(
                    spreadsheetId = spreadsheetId,
                    sheetId = sheet.id,
                    rowId = sheetRowId,
                    contentHash = contentHash,
                    operation = operation,
                    accessToken = accessToken
                )
            } else {
                api.updateCells(
                    spreadsheetId,
                    accessToken,
                    stateTable.valuesFor(
                        rowNumber = existingState.rowNumber,
                        sheetId = sheet.id,
                        rowId = sheetRowId,
                        contentHash = contentHash,
                        operation = operation
                    )
                )
            }
            api.clearStrikeThroughRow(
                spreadsheetId,
                sheetTitle,
                rowNumber,
                headers.values.maxOrNull() ?: 0,
                accessToken
            )
            run {
                val customerKey = listOf(row.lastName, row.firstName, row.contact)
                    .map(String::normalizedIdentity)
                val existingChildRows = existingRows.drop(headerRowIndex + 1)
                    .mapIndexedNotNull { index, cells ->
                        val candidateRowNumber = headerRowIndex + index + 2
                        val rowKey = listOf(
                            cells.valueAt(headers[HEADER_LAST_NAME]),
                            cells.valueAt(headers[HEADER_FIRST_NAME]),
                            cells.valueAt(headers[HEADER_CONTACT])
                        ).map(String::normalizedIdentity)
                        candidateRowNumber.takeIf {
                            candidateRowNumber != rowNumber &&
                                (rowIdsByRowNumber[candidateRowNumber] == sheetRowId ||
                                    (!row.isDoorSale && rowKey == customerKey))
                        }
                    }
                physicalRows.drop(1).forEachIndexed { index, child ->
                    val childRowNumber = existingChildRows.getOrNull(index)
                        ?: nextAvailableRow.also { availableRow ->
                            if (
                                availableRow > existingRows.size ||
                                summaryRowNumber?.let { availableRow >= it } == true
                            ) {
                                api.insertReservationRow(spreadsheetId, sheetTitle, availableRow, accessToken)
                            }
                            nextAvailableRow += 1
                        }
                    api.updateCells(
                        spreadsheetId,
                        accessToken,
                        child.toSheetCellValues(sheetTitle, childRowNumber, headers)
                    )
                    if (rowIdsByRowNumber[childRowNumber] != sheetRowId) {
                        api.attachRowIdentity(spreadsheetId, sheet.id, childRowNumber, sheetRowId, accessToken)
                    }
                    api.clearStrikeThroughRow(
                        spreadsheetId,
                        sheetTitle,
                        childRowNumber,
                        headers.values.maxOrNull() ?: 0,
                        accessToken
                    )
                }
                obsoleteChildRows += existingChildRows.drop(physicalRows.size - 1)
            }
            AppLog.debug(LOG_COMPONENT) {
                "Exported ${row.toLogSummary()}, targetRow=$rowNumber, addition=$isAddition, " +
                    "sheetRowId=${sheetRowId.toAbbreviatedId()}"
            }
            exportedRows += ExportedSpreadsheetRow(row.sourceIdentity, sheetRowId)
        }
        obsoleteChildRows.sortedDescending().forEach { rowNumber ->
            api.deleteReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
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
        row: ReservationSpreadsheetRow
    ) = withContext(Dispatchers.IO) {
        AppLog.info(LOG_COMPONENT) {
            "Starting soft deletion; tab=$sheetTitle, sheetRowId=${row.sheetRowId.toAbbreviatedId()}"
        }
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val structure = api.loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
        val sheet = requireNotNull(structure.sheetsByTitle[sheetTitle]) {
            "Välilehteä '$sheetTitle' ei enää ole."
        }
        val values = api.loadValuesForTabs(
            spreadsheetId,
            listOf(sheetTitle, APPLICATION_SHEET_TITLE),
            accessToken
        )
        val rows = values.getValue(sheetTitle)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.canonicalDataHeader() to index }
            .toMap()
        val rowIdsByRowNumber = structure.rowIdsBySheetId[sheet.id].orEmpty()
        val idMatchedRow = row.sheetRowId.takeIf(String::isNotBlank)?.let { rowId ->
            rowIdsByRowNumber.entries.firstOrNull { (_, remoteId) -> remoteId == rowId }?.key
        }
        val desiredCustomerKey = listOf(row.lastName, row.firstName, row.contact).map(String::normalizedIdentity)
        val rowNumber = idMatchedRow ?: rows.drop(headerRowIndex + 1).mapIndexedNotNull { index, cells ->
            val matchesSourceIdentity = !row.isDoorSale && desiredCustomerKey == listOf(
                cells.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity(),
                cells.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity(),
                cells.valueAt(headers[HEADER_CONTACT]).normalizedIdentity()
            )
            matchesSourceIdentity.takeIf { it }?.let { headerRowIndex + index + 2 }
        }.firstOrNull() ?: row.sourceIdentity.toLegacyDoorSaleDataRowIndexOrNull()
            ?.let { headerRowIndex + it + 2 }
            ?: return@withContext
        val customerKey = desiredCustomerKey
        val relatedChildRows = rows.drop(headerRowIndex + 1).mapIndexedNotNull { index, cells ->
            val candidateRowNumber = headerRowIndex + index + 2
            if (candidateRowNumber == rowNumber) return@mapIndexedNotNull null
            val candidateKey = listOf(
                cells.valueAt(headers[HEADER_LAST_NAME]),
                cells.valueAt(headers[HEADER_FIRST_NAME]),
                cells.valueAt(headers[HEADER_CONTACT])
            ).map(String::normalizedIdentity)
            candidateRowNumber.takeIf {
                if (row.isDoorSale) {
                    row.sheetRowId.isNotBlank() && rowIdsByRowNumber[candidateRowNumber] == row.sheetRowId
                } else {
                    rowIdsByRowNumber[candidateRowNumber] == row.sheetRowId || candidateKey == customerKey
                }
            }
        }
        if (HARD_DELETE_FROM_SHEET) {
            (relatedChildRows + rowNumber).sortedDescending().forEach { targetRow ->
                api.deleteReservationRow(spreadsheetId, sheetTitle, targetRow, accessToken)
            }
            return@withContext
        }
        val sheetRowId = rowIdsByRowNumber[rowNumber]
            ?: row.sheetRowId.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        if (rowNumber !in rowIdsByRowNumber) {
            api.attachRowIdentity(spreadsheetId, sheet.id, rowNumber, sheetRowId, accessToken)
        }
        val stateTable = values.getValue(APPLICATION_SHEET_TITLE).toApplicationRowStateTable()
        val existingState = stateTable.stateFor(sheet.id, sheetRowId)
        val contentHash = row.copy(sheetRowId = sheetRowId).sheetContentHash()
        if (existingState == null) {
            api.appendApplicationRowState(
                spreadsheetId = spreadsheetId,
                sheetId = sheet.id,
                rowId = sheetRowId,
                contentHash = contentHash,
                operation = APP_OPERATION_DELETE,
                accessToken = accessToken
            )
        } else {
            api.updateCells(
                spreadsheetId,
                accessToken,
                stateTable.valuesFor(
                    rowNumber = existingState.rowNumber,
                    sheetId = sheet.id,
                    rowId = sheetRowId,
                    contentHash = contentHash,
                    operation = APP_OPERATION_DELETE
                )
            )
        }
        api.strikeThroughRow(spreadsheetId, sheetTitle, rowNumber, headers.values.maxOrNull() ?: 0, accessToken)
        relatedChildRows.sortedDescending().forEach { childRowNumber ->
            api.deleteReservationRow(spreadsheetId, sheetTitle, childRowNumber, accessToken)
        }
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
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val structure = api.loadSpreadsheetMetadata(spreadsheetId, accessToken).toSpreadsheetStructure()
        val sheet = requireNotNull(structure.sheetsByTitle[sheetTitle]) {
            "Välilehteä '$sheetTitle' ei enää ole."
        }
        val values = api.loadValuesForTabs(
            spreadsheetId,
            listOf(sheetTitle, APPLICATION_SHEET_TITLE),
            accessToken
        )
        val rows = values.getValue(sheetTitle)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.canonicalDataHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.canonicalDataHeader() to index }
            .toMap()
        val rowIdsByRowNumber = structure.rowIdsBySheetId[sheet.id].orEmpty()
        val idMatchedRow = sheetRowId.takeIf(String::isNotBlank)?.let { rowId ->
            rowIdsByRowNumber.entries.firstOrNull { (_, remoteId) -> remoteId == rowId }?.key
        }
        val rowNumber = idMatchedRow ?: rows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val matchesSourceIdentity = sourceIdentity.toNameKeyOrNull() == (
                row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity() to
                    row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity()
                )
            matchesSourceIdentity.takeIf { it }?.let { headerRowIndex + index + 2 }
        }.firstOrNull() ?: return@withContext
        val effectiveRowId = rowIdsByRowNumber[rowNumber].orEmpty()
        val stateTable = values.getValue(APPLICATION_SHEET_TITLE).toApplicationRowStateTable()
        stateTable.stateFor(sheet.id, effectiveRowId)?.let { state ->
            api.updateCells(spreadsheetId, accessToken, stateTable.clearValuesFor(state.rowNumber))
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
    ): List<GoogleSheetImportData> = importer.loadAll(spreadsheetUrl, accessToken, localAliases)

    suspend fun loadImportDataForTab(
        spreadsheetUrl: String,
        accessToken: String,
        sheetTitle: String,
        localAliases: Map<String, StoredSheetAlias> = emptyMap()
    ): GoogleSheetImportData = importer.loadOne(spreadsheetUrl, accessToken, sheetTitle, localAliases)

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
