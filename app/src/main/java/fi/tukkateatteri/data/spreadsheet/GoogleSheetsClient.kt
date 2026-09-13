package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toAbbreviatedId
import fi.tukkateatteri.logging.toLogSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class GoogleSheetsClient(
    deviceId: String = "Android"
) {
    internal val aliasRegistryLocks = KeyedMutex()
    internal val cachedRemoteAliases = ConcurrentHashMap<String, MutableSet<String>>()
    internal val api = GoogleSheetsApiClient()
    private val importer = GoogleSheetImporter(api)
    private val performanceLock = GoogleSheetPerformanceLock(api, deviceId)

    suspend fun <T> withPerformanceLock(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T = performanceLock.withLock(spreadsheetUrl, sheetTitle, accessToken, action)

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
        val context = api.loadEditContext(spreadsheetId, sheetTitle, accessToken)
        val existingRows = context.rows
        val headerRowIndex = context.headerRowIndex
        val headers = context.headers
        val rowIdsByRowNumber = context.rowIdsByRowNumber
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
        val stateTable = context.stateTable
        val stateRowsById = stateTable.rows
            .filter { state -> state.sheetId == context.sheet.id }
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
                AppLog.debug(LOG_COMPONENT) {
                    "Inserting reservation row before summary; tab=$sheetTitle, row=$rowNumber"
                }
                api.insertReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            }
            if (rowNumber !in rowIdsByRowNumber) {
                api.attachRowIdentity(spreadsheetId, context.sheet.id, rowNumber, sheetRowId, accessToken)
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
                    sheetId = context.sheet.id,
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
                        sheetId = context.sheet.id,
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
                        api.attachRowIdentity(
                            spreadsheetId,
                            context.sheet.id,
                            childRowNumber,
                            sheetRowId,
                            accessToken
                        )
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
        val context = api.loadEditContext(spreadsheetId, sheetTitle, accessToken)
        val rowIdsByRowNumber = context.rowIdsByRowNumber
        val idMatchedRow = context.rowNumberForId(row.sheetRowId)
        val desiredCustomerKey = listOf(row.lastName, row.firstName, row.contact).map(String::normalizedIdentity)
        val rowNumber = idMatchedRow ?: context.reservationRows.firstOrNull { candidate ->
            !row.isDoorSale && desiredCustomerKey == candidate.customerIdentity(context.headers)
        }?.rowNumber ?: row.sourceIdentity.toLegacyDoorSaleDataRowIndexOrNull()
            ?.let { context.headerRowIndex + it + 2 }
            ?: return@withContext
        val relatedChildRows = context.reservationRows.mapNotNull { candidate ->
            if (candidate.rowNumber == rowNumber) return@mapNotNull null
            candidate.rowNumber.takeIf {
                if (row.isDoorSale) {
                    row.sheetRowId.isNotBlank() &&
                        rowIdsByRowNumber[candidate.rowNumber] == row.sheetRowId
                } else {
                    rowIdsByRowNumber[candidate.rowNumber] == row.sheetRowId ||
                        candidate.customerIdentity(context.headers) == desiredCustomerKey
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
            api.attachRowIdentity(spreadsheetId, context.sheet.id, rowNumber, sheetRowId, accessToken)
        }
        val stateTable = context.stateTable
        val existingState = stateTable.stateFor(context.sheet.id, sheetRowId)
        val contentHash = row.copy(sheetRowId = sheetRowId).sheetContentHash()
        if (existingState == null) {
            api.appendApplicationRowState(
                spreadsheetId = spreadsheetId,
                sheetId = context.sheet.id,
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
                    sheetId = context.sheet.id,
                    rowId = sheetRowId,
                    contentHash = contentHash,
                    operation = APP_OPERATION_DELETE
                )
            )
        }
        api.strikeThroughRow(
            spreadsheetId,
            sheetTitle,
            rowNumber,
            context.lastColumnIndex,
            accessToken
        )
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
        val context = api.loadEditContext(spreadsheetId, sheetTitle, accessToken)
        val rowNumber = context.rowNumberForId(sheetRowId) ?: context.reservationRows.firstOrNull { row ->
            sourceIdentity.toCustomerIdentityOrNull()?.matches(
                lastName = row.cells.valueAt(context.headers[HEADER_LAST_NAME]).normalizedIdentity(),
                firstName = row.cells.valueAt(context.headers[HEADER_FIRST_NAME]).normalizedIdentity(),
                contact = row.cells.valueAt(context.headers[HEADER_CONTACT]).normalizedIdentity()
            ) == true
        }?.rowNumber ?: return@withContext
        val effectiveRowId = context.rowIdsByRowNumber[rowNumber].orEmpty()
        context.stateTable.stateFor(context.sheet.id, effectiveRowId)?.let { state ->
            api.updateCells(
                spreadsheetId,
                accessToken,
                context.stateTable.clearValuesFor(state.rowNumber)
            )
        }
        api.clearStrikeThroughRow(
            spreadsheetId,
            sheetTitle,
            rowNumber,
            context.lastColumnIndex,
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

    private companion object {
        const val LOG_COMPONENT = "Sheets"
        const val HARD_DELETE_FROM_SHEET = false
    }
}
