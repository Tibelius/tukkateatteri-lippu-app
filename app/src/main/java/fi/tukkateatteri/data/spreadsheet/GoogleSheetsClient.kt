package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.MINIMUM_SEAT_COUNT
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.toPerformanceDateOrNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

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
    val rows: List<ReservationSpreadsheetRow>
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

data class ExportedSpreadsheetRow(
    val sourceIdentity: String,
    val sheetRowId: String
)

class GoogleSheetsClient(
    private val deviceId: String = "Android"
) {
    private val localPerformanceLocks = KeyedMutex()

    private suspend fun loadTabs(
        spreadsheetUrl: String,
        accessToken: String
    ): List<GoogleSheetTab> = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val metadata = getJson(
            url = "$API_BASE/spreadsheets/$spreadsheetId?includeGridData=false",
            accessToken = accessToken
        )
        val titles = buildList {
            val sheets = metadata.getJSONArray("sheets")
            for (index in 0 until sheets.length()) {
                add(sheets.getJSONObject(index).getJSONObject("properties").getString("title"))
            }
        }
        val rowsByTitle = loadValuesForTabs(spreadsheetId, titles, accessToken)
        return@withContext titles.map { title -> GoogleSheetTab(title, rowsByTitle.getValue(title)) }
    }

    suspend fun <T> withPerformanceLock(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val performanceKey = performanceLockKey(spreadsheetId, sheetTitle)
        localPerformanceLocks.withLock(performanceKey) {
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
        var lockTable = loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        if (removeExpiredLocks(spreadsheetId, lockTable, now, accessToken)) {
            lockTable = loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        }
        val existingLock = lockTable.activeLockFor(performanceKey, now)
        if (existingLock != null) {
            if (existingLock.deviceId != deviceId) {
                throw GoogleSheetLockedException(
                    failure = GoogleSheetLockFailure.HELD_BY_ANOTHER_DEVICE,
                    sheetTitle = sheetTitle,
                    holderDeviceId = existingLock.deviceId
                )
            }
            lockId = existingLock.lockId.ifBlank { lockId }
        }

        val expiresAt = now.plusSeconds(LOCK_DURATION_SECONDS)
        if (existingLock == null) {
            appendPerformanceLock(
                spreadsheetId = spreadsheetId,
                accessToken = accessToken,
                rowValues = lockTable.rowValuesFor(
                    performanceKey = performanceKey,
                    lockId = lockId,
                    lockedAt = now.toString(),
                    expiresAt = expiresAt.toString(),
                    deviceId = deviceId
                )
            )
        } else {
            updateCells(
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
        val confirmedLock = loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken)
            .toLockTable()
            .rowsByPerformanceKey[performanceKey]
        if (confirmedLock?.lockId != lockId || confirmedLock.deviceId != deviceId) {
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.ACQUISITION_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = confirmedLock?.deviceId
            )
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
            releasePerformanceLock(spreadsheetId, performanceKey, lockId, accessToken)
        }
    }

    suspend fun exportRows(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        rows: List<ReservationSpreadsheetRow>
    ): List<ExportedSpreadsheetRow> = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val existingRows = loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = existingRows.indexOfFirst { row -> row.any { cell -> cell.normalizedHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = ensureApplicationHeaders(spreadsheetId, sheetTitle, existingRows, headerRowIndex, accessToken)
        requireApplicationHeaders(headers)
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
        var nextAvailableRow = firstAvailableReservationRow(existingRows, headerRowIndex)
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
                insertReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            }
            updateCells(
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
            exportedRows += ExportedSpreadsheetRow(row.sourceIdentity, sheetRowId)
        }
        exportedRows
    }

    suspend fun softDeleteRow(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        sheetRowId: String,
        sourceIdentity: String
    ) = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val rows = loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.normalizedHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.normalizedHeader() to index }
            .toMap()
        requireApplicationHeaders(headers)
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
            deleteReservationRow(spreadsheetId, sheetTitle, rowNumber, accessToken)
            return@withContext
        }
        val mutationId = UUID.randomUUID().toString()
        updateCells(
            spreadsheetId,
            accessToken,
            deletedRowCellValues(sheetTitle, rowNumber, headers, mutationId)
        )
        strikeThroughRow(spreadsheetId, sheetTitle, rowNumber, headers.values.maxOrNull() ?: 0, accessToken)
    }

    /** Removes app-only markers after a direct Sheet edit; reservation values remain untouched. */
    suspend fun clearApplicationMetadata(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        sheetRowId: String,
        sourceIdentity: String
    ) = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val rows = loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = rows.indexOfFirst { row -> row.any { it.normalizedHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = rows[headerRowIndex]
            .mapIndexed { index, header -> header.normalizedHeader() to index }
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
            updateCells(spreadsheetId, accessToken, metadataCells)
        }
        clearStrikeThroughRow(
            spreadsheetId,
            sheetTitle,
            rowNumber,
            headers.values.maxOrNull() ?: 0,
            accessToken
        )
    }

    suspend fun clearManualRowStrikethrough(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        rowNumbers: Collection<Int>
    ) = withContext(Dispatchers.IO) {
        if (rowNumbers.isEmpty()) return@withContext
        setRowsStrikethrough(
            spreadsheetId = spreadsheetUrl.toSpreadsheetId(),
            sheetTitle = sheetTitle,
            rowNumbers = rowNumbers,
            lastColumnIndex = SHEET_VALUE_LAST_COLUMN_INDEX,
            enabled = false,
            accessToken = accessToken
        )
    }

    suspend fun loadImportData(spreadsheetUrl: String, accessToken: String): List<GoogleSheetImportData> =
        loadTabs(spreadsheetUrl, accessToken)
            .mapNotNull { tab ->
                tab.toImportCandidateOrNull()?.let { candidate ->
                    GoogleSheetImportData(
                        candidate = candidate,
                        rows = tab.toReservationSpreadsheetRows(candidate)
                    )
                }
            }
            .sortedWith(
                compareBy<GoogleSheetImportData> { it.candidate.sortDate ?: LocalDate.MAX }
                    .thenBy { it.candidate.date }
            )

    suspend fun loadImportDataForTab(
        spreadsheetUrl: String,
        accessToken: String,
        sheetTitle: String
    ): GoogleSheetImportData = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val tab = GoogleSheetTab(
            title = sheetTitle,
            rows = loadValuesForTabs(spreadsheetId, listOf(sheetTitle), accessToken).getValue(sheetTitle)
        )
        val candidate = requireNotNull(tab.toImportCandidateOrNull()) {
            "Välilehdeltä puuttuu Esitys: tai Pvm: -tieto."
        }
        GoogleSheetImportData(candidate, tab.toReservationSpreadsheetRows(candidate))
    }

    private fun loadValues(spreadsheetId: String, title: String, accessToken: String): List<List<String>> {
        val range = URLEncoder.encode(valuesRange(title), Charsets.UTF_8.name())
        val response = getJson("$API_BASE/spreadsheets/$spreadsheetId/values/$range", accessToken)
        return response.optJSONArray("values").toRows()
    }

    private fun loadValuesForTabs(
        spreadsheetId: String,
        titles: List<String>,
        accessToken: String
    ): Map<String, List<List<String>>> {
        if (titles.isEmpty()) return emptyMap()
        val ranges = titles.joinToString("&") { title ->
            "ranges=${URLEncoder.encode(valuesRange(title), Charsets.UTF_8.name())}"
        }
        val response = getJson("$API_BASE/spreadsheets/$spreadsheetId/values:batchGet?$ranges", accessToken)
        val valueRanges = response.optJSONArray("valueRanges")
        return titles.mapIndexed { index, title ->
            title to valueRanges?.optJSONObject(index)?.optJSONArray("values").toRows()
        }.toMap()
    }

    private fun JSONArray?.toRows(): List<List<String>> {
        val values = this ?: return emptyList()
        return buildList {
            for (rowIndex in 0 until values.length()) {
                val row = values.getJSONArray(rowIndex)
                add(buildList {
                    for (cellIndex in 0 until row.length()) add(row.getString(cellIndex))
                })
            }
        }
    }

    private fun valuesRange(sheetTitle: String): String =
        "${sheetTitle.toQuotedSheetName()}!$SHEET_VALUE_COLUMNS"

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = openConnection(url, accessToken)
        connection.requestMethod = HTTP_GET
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val responseCode = connection.responseCode
            val response = connection.responseText(responseCode)
            if (responseCode !in HTTP_SUCCESS_CODES) {
                throw IllegalStateException("Google Sheets -pyyntö epäonnistui ($responseCode): $response")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureApplicationHeaders(
        spreadsheetId: String,
        sheetTitle: String,
        rows: List<List<String>>,
        headerRowIndex: Int,
        accessToken: String
    ): Map<String, Int> {
        val headers = rows[headerRowIndex].mapIndexed { index, header -> header.normalizedHeader() to index }.toMap().toMutableMap()
        if (HEADER_SHEET_ROW_ID !in headers) {
            val columnIndex = (headers.values.maxOrNull() ?: -1) + 1
            updateCells(
                spreadsheetId,
                accessToken,
                listOf(
                    SheetCellValue(
                        sheetCellRange(sheetTitle, columnIndex, headerRowIndex + 1),
                        "Sovellus-ID"
                    )
                )
            )
            headers[HEADER_SHEET_ROW_ID] = columnIndex
        }
        return headers
    }

    private fun requireApplicationHeaders(headers: Map<String, Int>) {
        val missingHeaders = listOf(
            HEADER_SHEET_ROW_ID,
            HEADER_APP_OPERATION,
            HEADER_APP_MODIFIED_AT,
            HEADER_APP_MUTATION_ID
        ).filterNot(headers::containsKey)
        require(missingHeaders.isEmpty()) {
            "Välilehdeltä puuttuvat sovellussarakkeet: ${missingHeaders.joinToString()}."
        }
    }

    private fun firstAvailableReservationRow(rows: List<List<String>>, headerRowIndex: Int): Int {
        val firstDataRowIndex = headerRowIndex + 1
        val summaryRowIndex = rows.indexOfFirst { row ->
            row.any { cell -> cell.normalizedHeader().startsWith(HEADER_RESERVATION_TOTAL) }
        }.takeIf { it >= firstDataRowIndex } ?: rows.size
        val blankRowIndex = (firstDataRowIndex until summaryRowIndex).firstOrNull { rowIndex ->
            rows[rowIndex].valueAt(0).isBlank() && rows[rowIndex].valueAt(1).isBlank()
        }
        return (blankRowIndex ?: summaryRowIndex) + 1
    }

    private fun insertReservationRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        accessToken: String
    ) {
        val sheetId = loadSheetId(spreadsheetId, sheetTitle, accessToken)
        val request = JSONObject().put(
            "requests",
            JSONArray().put(
                JSONObject().put(
                    "insertDimension",
                    JSONObject().put(
                        "range",
                        JSONObject()
                            .put("sheetId", sheetId)
                            .put("dimension", SHEET_DIMENSION_ROWS)
                            .put("startIndex", rowNumber - 1)
                            .put("endIndex", rowNumber)
                    ).put("inheritFromBefore", true)
                )
            )
        )
        sendJson("$API_BASE/spreadsheets/$spreadsheetId:batchUpdate", HTTP_POST, request, accessToken)
    }

    private fun deleteReservationRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        accessToken: String
    ) {
        val sheetId = loadSheetId(spreadsheetId, sheetTitle, accessToken)
        val request = JSONObject().put(
            "requests",
            JSONArray().put(
                JSONObject().put(
                    "deleteDimension",
                    JSONObject().put(
                        "range",
                        JSONObject()
                            .put("sheetId", sheetId)
                            .put("dimension", SHEET_DIMENSION_ROWS)
                            .put("startIndex", rowNumber - 1)
                            .put("endIndex", rowNumber)
                    )
                )
            )
        )
        sendJson("$API_BASE/spreadsheets/$spreadsheetId:batchUpdate", HTTP_POST, request, accessToken)
    }

    private fun updateCells(
        spreadsheetId: String,
        accessToken: String,
        values: List<SheetCellValue>
    ) {
        if (values.isEmpty()) return
        val request = JSONObject()
            .put("valueInputOption", "RAW")
            .put(
                "data",
                JSONArray().apply {
                    values.forEach { value ->
                        put(
                            JSONObject()
                                .put("range", value.range)
                                .put("values", JSONArray().put(JSONArray().put(value.value)))
                        )
                    }
                }
            )
        sendJson("$API_BASE/spreadsheets/$spreadsheetId/values:batchUpdate", HTTP_POST, request, accessToken)
    }

    private fun appendPerformanceLock(
        spreadsheetId: String,
        accessToken: String,
        rowValues: List<String>
    ) {
        val range = URLEncoder.encode(valuesRange(LOCK_SHEET_TITLE), Charsets.UTF_8.name())
        val request = JSONObject()
            .put("majorDimension", SHEET_DIMENSION_ROWS)
            .put("values", JSONArray().put(JSONArray(rowValues)))
        sendJson(
            "$API_BASE/spreadsheets/$spreadsheetId/values/$range:append" +
                "?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
            HTTP_POST,
            request,
            accessToken
        )
    }

    private fun removeExpiredLocks(
        spreadsheetId: String,
        lockTable: LockTable,
        now: Instant,
        accessToken: String
    ): Boolean {
        val expiredLocks = lockTable.rows.filter { lock ->
            lock.lockId.isBlank() || lock.expiresAt?.let { !it.isAfter(now) } == true
        }
        if (expiredLocks.isEmpty()) return false
        updateCells(
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
        val lockTable = loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.rowsByPerformanceKey[performanceKey] ?: return
        if (existingLock.lockId != lockId) return
        updateCells(
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
        val lockTable = loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.rowsByPerformanceKey[performanceKey]
        if (existingLock?.lockId != lockId || existingLock.deviceId != deviceId) {
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.RENEWAL_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = existingLock?.deviceId
            )
        }
        val now = Instant.now()
        updateCells(
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
    }

    private fun strikeThroughRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        lastColumnIndex: Int,
        accessToken: String
    ) {
        setRowsStrikethrough(
            spreadsheetId = spreadsheetId,
            sheetTitle = sheetTitle,
            rowNumbers = listOf(rowNumber),
            lastColumnIndex = lastColumnIndex,
            enabled = true,
            accessToken = accessToken
        )
    }

    private fun clearStrikeThroughRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        lastColumnIndex: Int,
        accessToken: String
    ) {
        setRowsStrikethrough(
            spreadsheetId = spreadsheetId,
            sheetTitle = sheetTitle,
            rowNumbers = listOf(rowNumber),
            lastColumnIndex = lastColumnIndex,
            enabled = false,
            accessToken = accessToken
        )
    }

    private fun setRowsStrikethrough(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumbers: Collection<Int>,
        lastColumnIndex: Int,
        enabled: Boolean,
        accessToken: String
    ) {
        val sheetId = loadSheetId(spreadsheetId, sheetTitle, accessToken)
        val requests = JSONArray()
        rowNumbers.asSequence().filter { it > 0 }.distinct().forEach { rowNumber ->
            requests.put(
                JSONObject().put(
                    "repeatCell",
                    JSONObject()
                        .put(
                            "range",
                            JSONObject()
                                .put("sheetId", sheetId)
                                .put("startRowIndex", rowNumber - 1)
                                .put("endRowIndex", rowNumber)
                                .put("startColumnIndex", 0)
                                .put("endColumnIndex", lastColumnIndex + 1)
                        )
                        .put(
                            "cell",
                            JSONObject().put(
                                "userEnteredFormat",
                                JSONObject().put("textFormat", JSONObject().put("strikethrough", enabled))
                            )
                        )
                        .put("fields", "userEnteredFormat.textFormat.strikethrough")
                )
            )
        }
        if (requests.length() == 0) return
        val request = JSONObject().put(
            "requests",
            requests
        )
        sendJson("$API_BASE/spreadsheets/$spreadsheetId:batchUpdate", HTTP_POST, request, accessToken)
    }

    private fun loadSheetId(
        spreadsheetId: String,
        sheetTitle: String,
        accessToken: String
    ): Int {
        val sheets = getJson(
            "$API_BASE/spreadsheets/$spreadsheetId?includeGridData=false",
            accessToken
        ).getJSONArray("sheets")
        return (0 until sheets.length())
            .asSequence()
            .map { index -> sheets.getJSONObject(index).getJSONObject("properties") }
            .first { properties -> properties.getString("title") == sheetTitle }
            .getInt("sheetId")
    }

    private fun sendJson(url: String, method: String, request: JSONObject, accessToken: String) {
        val requestBody = request.toString().toByteArray()
        val connection = openConnection(url, accessToken)
        try {
            connection.requestMethod = method
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody) }
            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_CODES) {
                throw IllegalStateException(
                    "Google Sheets -pyyntö epäonnistui ($responseCode): " +
                        connection.responseText(responseCode)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accessToken: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $accessToken")
        }

    private fun HttpURLConnection.responseText(responseCode: Int): String {
        val stream = if (responseCode in HTTP_SUCCESS_CODES) inputStream else errorStream
        return stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
    }

    companion object {
        private const val API_BASE = "https://sheets.googleapis.com/v4"
        private const val LOCK_DURATION_SECONDS = 30L
        private const val LOCK_RENEWAL_INTERVAL_MILLIS = 10_000L
        private const val HARD_DELETE_FROM_SHEET = false
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val HTTP_GET = "GET"
        private const val HTTP_POST = "POST"
        private const val SHEET_DIMENSION_ROWS = "ROWS"
        private const val SHEET_VALUE_COLUMNS = "A:Z"
        private const val SHEET_VALUE_LAST_COLUMN_INDEX = 25
        private val HTTP_SUCCESS_CODES = 200..299
    }
}

fun GoogleSheetTab.toImportCandidateOrNull(): GoogleSheetImportCandidate? {
    val performanceName = valueRightOfLabel(HEADER_PERFORMANCE) ?: return null
    val date = valueRightOfLabel(HEADER_DATE) ?: return null
    return GoogleSheetImportCandidate(
        sheetTitle = title,
        performanceName = performanceName,
        date = date,
        sortDate = date.toPerformanceDateOrNull()
    )
}

fun GoogleSheetTab.toReservationSpreadsheetRows(candidate: GoogleSheetImportCandidate): List<ReservationSpreadsheetRow> {
    val headerRowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.normalizedHeader() == HEADER_LAST_NAME } }
    if (headerRowIndex < 0) return emptyList()
    val headerIndexes = rows[headerRowIndex].mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    return rows.drop(headerRowIndex + 1).takeWhile { row ->
        row.valueAt(headerIndexes[HEADER_LAST_NAME]).isNotBlank() || row.valueAt(headerIndexes[HEADER_FIRST_NAME]).isNotBlank()
    }.mapIndexedNotNull { dataRowIndex, row ->
        val metadataState = row.applicationMutationMetadataState(headerIndexes)
        if (
            metadataState == ApplicationMutationMetadataState.VALID &&
            row.valueAt(headerIndexes[HEADER_APP_OPERATION]).normalizedHeader() == APP_OPERATION_DELETE.normalizedHeader()
        ) {
            return@mapIndexedNotNull null
        }
        val lastName = row.valueAt(headerIndexes[HEADER_LAST_NAME]).trimSpreadsheetWhitespace()
        val firstName = row.valueAt(headerIndexes[HEADER_FIRST_NAME]).trimSpreadsheetWhitespace()
        if (lastName.isBlank() && firstName.isBlank()) return@mapIndexedNotNull null
        val sheetRowId = row.valueAt(headerIndexes[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace()
        val sourceIdentity = if (sheetRowId.isUuid()) {
            "sheet:$sheetRowId"
        } else if (lastName.isDoorSaleSheetLabel()) {
            "${candidate.performanceName.normalizedIdentity()}|${candidate.date.normalizedIdentity()}|ovelta|$dataRowIndex"
        } else {
            "${candidate.performanceName.normalizedIdentity()}|${candidate.date.normalizedIdentity()}|${lastName.normalizedIdentity()}|${firstName.normalizedIdentity()}"
        }
        ReservationSpreadsheetRow(
            lastName = lastName,
            firstName = firstName,
            contact = row.valueAt(headerIndexes[HEADER_CONTACT]).trimSpreadsheetWhitespace(),
            reservedSeatCount = row.valueAt(headerIndexes[HEADER_RESERVED_COUNT])
                .toTicketCount()
                .coerceAtLeast(MINIMUM_SEAT_COUNT),
            arrivalCount = row.valueAt(headerIndexes[HEADER_ARRIVAL_COUNT]).toTicketCount(),
            reservedTicketCounts = ticketHeaders.mapNotNull { (ticketType, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { ticketType to it }
            }.toMap(),
            paymentTicketCounts = paymentHeaders.mapNotNull { (paymentMethod, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { paymentMethod to it }
            }.toMap(),
            notes = row.valueAt(headerIndexes[HEADER_NOTES]).trimSpreadsheetWhitespace(),
            sourceIdentity = sourceIdentity,
            sheetRowId = sheetRowId,
            sourceRowNumber = headerRowIndex + dataRowIndex + 2,
            applicationMutationMetadataState = metadataState
        )
    }
}

private fun List<String>.applicationMutationMetadataState(headers: Map<String, Int>): ApplicationMutationMetadataState {
    val sheetRowId = valueAt(headers[HEADER_SHEET_ROW_ID]).trimSpreadsheetWhitespace()
    val operation = valueAt(headers[HEADER_APP_OPERATION]).normalizedHeader()
    val modifiedAt = valueAt(headers[HEADER_APP_MODIFIED_AT]).trimSpreadsheetWhitespace()
    val mutationId = valueAt(headers[HEADER_APP_MUTATION_ID]).trimSpreadsheetWhitespace()
    val values = listOf(sheetRowId, operation, modifiedAt, mutationId)
    if (values.all(String::isBlank)) return ApplicationMutationMetadataState.NONE

    val validOperation = operation.isBlank() || operation in VALID_APPLICATION_OPERATIONS
    val validTimestamp = runCatching { Instant.parse(modifiedAt) }.isSuccess
    val validSheetRowId = sheetRowId.isUuid()
    val validMutationId = mutationId.isUuid()
    return if (validOperation && validTimestamp && validSheetRowId && validMutationId) {
        ApplicationMutationMetadataState.VALID
    } else {
        ApplicationMutationMetadataState.INVALID
    }
}

private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess

private fun GoogleSheetTab.valueRightOfLabel(label: String): String? = rows.firstNotNullOfOrNull { row ->
    row.indexOfFirst { value -> value.normalizedHeader() == label }
        .takeIf { index -> index >= 0 }
        ?.let { index -> row.getOrNull(index + 1)?.trimSpreadsheetWhitespace() }
        ?.takeIf(String::isNotBlank)
}

private fun String.isDoorSaleSheetLabel(): Boolean = normalizedIdentity() in setOf(
    DOOR_SALE_SHEET_LABEL.normalizedIdentity(),
    LEGACY_DOOR_SALE_SHEET_LABEL.normalizedIdentity()
)

private fun ReservationSpreadsheetRow.toSheetCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>,
    sheetRowId: String,
    operation: String?
): List<SheetCellValue> = buildList {
    fun set(header: String, value: Any) {
        headers[header]?.let { columnIndex ->
            add(SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), value))
        }
    }
    set(HEADER_LAST_NAME, lastName)
    set(HEADER_FIRST_NAME, firstName)
    set(HEADER_CONTACT, contact)
    set(HEADER_RESERVED_COUNT, reservedSeatCount)
    set(HEADER_ARRIVAL_COUNT, arrivalCount)
    ticketHeaders.forEach { (type, header) -> set(header, reservedTicketCounts[type] ?: "") }
    paymentHeaders.forEach { (method, header) -> set(header, paymentTicketCounts[method] ?: "") }
    set(HEADER_NOTES, notes)
    set(HEADER_SHEET_ROW_ID, sheetRowId)
    operation?.let { set(HEADER_APP_OPERATION, it) }
    set(HEADER_APP_MODIFIED_AT, Instant.now().toString())
    set(HEADER_APP_MUTATION_ID, UUID.randomUUID().toString())
}

private fun deletedRowCellValues(
    sheetTitle: String,
    rowNumber: Int,
    headers: Map<String, Int>,
    mutationId: String
): List<SheetCellValue> = buildList {
    fun set(header: String, value: Any) {
        headers[header]?.let { columnIndex ->
            add(SheetCellValue(sheetCellRange(sheetTitle, columnIndex, rowNumber), value))
        }
    }
    set(HEADER_RESERVED_COUNT, 0)
    set(HEADER_ARRIVAL_COUNT, 0)
    ticketHeaders.values.forEach { set(it, 0) }
    paymentHeaders.values.forEach { set(it, 0) }
    set(HEADER_APP_OPERATION, APP_OPERATION_DELETE)
    set(HEADER_APP_MODIFIED_AT, Instant.now().toString())
    set(HEADER_APP_MUTATION_ID, mutationId)
}

private data class SheetCellValue(val range: String, val value: Any) {
    init {
        require(value is String || value is Int) { "Only text and whole numbers can be written to Google Sheets." }
    }
}

private data class LockRow(
    val performanceKey: String,
    val rowNumber: Int,
    val lockId: String,
    val expiresAt: Instant?,
    val deviceId: String
)

private data class LockTable(
    val headers: Map<String, Int>,
    val rows: List<LockRow>,
    val rowsByPerformanceKey: Map<String, LockRow>
) {
    fun activeLockFor(performanceKey: String, now: Instant): LockRow? = rows.lastOrNull { lock ->
        lock.performanceKey == performanceKey && lock.expiresAt?.isAfter(now) == true
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

    fun rowValuesFor(
        performanceKey: String,
        lockId: String,
        lockedAt: String,
        expiresAt: String,
        deviceId: String
    ): List<String> = MutableList((headers.values.maxOrNull() ?: -1) + 1) { "" }.apply {
        fun set(header: String, value: String) {
            headers[header]?.let { columnIndex -> this@apply[columnIndex] = value }
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

private fun List<List<String>>.toLockTable(): LockTable {
    val headerRowIndex = indexOfFirst { row -> row.any { it.normalizedHeader() == LOCK_HEADER_PERFORMANCE_ID } }
    require(headerRowIndex >= 0) { "Sovelluslukot-välilehdeltä puuttuu performance_id-sarake." }
    val headers = get(headerRowIndex).mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    require(REQUIRED_LOCK_HEADERS.all(headers::containsKey)) {
        "Sovelluslukot-välilehden otsikot eivät vastaa sovittua muotoa."
    }
    val rows = drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
        val performanceKey = row.valueAt(headers[LOCK_HEADER_PERFORMANCE_ID]).trimSpreadsheetWhitespace()
        performanceKey.takeIf(String::isNotBlank)?.let {
            LockRow(
                performanceKey = performanceKey,
                rowNumber = headerRowIndex + index + 2,
                lockId = row.valueAt(headers[LOCK_HEADER_UUID]).trimSpreadsheetWhitespace(),
                expiresAt = runCatching {
                    Instant.parse(row.valueAt(headers[LOCK_HEADER_EXPIRES_AT]).trimSpreadsheetWhitespace())
                }.getOrNull(),
                deviceId = row.valueAt(headers[LOCK_HEADER_DEVICE_LABEL]).trimSpreadsheetWhitespace()
            )
        }
    }
    return LockTable(
        headers = headers,
        rows = rows,
        rowsByPerformanceKey = rows.associateBy(LockRow::performanceKey)
    )
}

private fun String.toNameKeyOrNull(): Pair<String, String>? = split("|")
    .takeIf { it.size >= 4 }
    ?.let { parts -> parts[parts.lastIndex - 1] to parts.last() }

private fun String.toLegacyDoorSaleDataRowIndexOrNull(): Int? = split("|")
    .takeIf { parts -> parts.size == 4 && parts[2] == "ovelta" }
    ?.lastOrNull()
    ?.toIntOrNull()

private fun sheetCellRange(sheetTitle: String, columnIndex: Int, rowNumber: Int): String =
    "${sheetTitle.toQuotedSheetName()}!${columnIndex.toColumnName()}$rowNumber"

private fun String.toQuotedSheetName(): String = "'${replace("'", "''")}'"

private fun Int.toColumnName(): String {
    var value = this + 1
    return buildString {
        while (value > 0) {
            value -= 1
            append(('A'.code + (value % 26)).toChar())
            value /= 26
        }
    }.reversed()
}

private fun String.toSpreadsheetId(): String {
    val match = SPREADSHEET_ID_REGEX.find(this)
        ?: throw IllegalArgumentException("Google Sheets -osoite ei ole kelvollinen.")
    return match.groupValues[1]
}

private fun String.normalizedHeader(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
    .lowercase()
    .replace("*", "")
    .trim()

private fun String.normalizedIdentity(): String = trimSpreadsheetWhitespace()
    .replace(SPREADSHEET_WHITESPACE_REGEX, " ")
    .lowercase()

private fun String.trimSpreadsheetWhitespace(): String = trim { character ->
    character.isWhitespace() || character == NON_BREAKING_SPACE
}

private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

private fun String.toTicketCount(): Int {
    val normalizedValue = trimSpreadsheetWhitespace().lowercase()
    return normalizedValue.toIntOrNull() ?: if (normalizedValue in TICKET_MARKERS) 1 else 0
}

private val SPREADSHEET_ID_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")
private val SPREADSHEET_WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")
private val TICKET_MARKERS = setOf("x", "✓", "k")
private const val NON_BREAKING_SPACE = '\u00A0'

internal fun performanceLockKey(spreadsheetId: String, sheetTitle: String): String =
    "$spreadsheetId|${sheetTitle.trimSpreadsheetWhitespace()}"

private const val LOCK_SHEET_TITLE = "Sovelluslukot"
private const val HEADER_LAST_NAME = "sukunimi"
private const val HEADER_FIRST_NAME = "etunimi"
private const val HEADER_CONTACT = "yhteystiedot"
private const val HEADER_RESERVED_COUNT = "varatut liput kpl"
private const val HEADER_ARRIVAL_COUNT = "saapunut esitykseen eli lunastettujen lippujen lukumäärä"
private const val HEADER_NOTES = "huom! (merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
private const val HEADER_SHEET_ROW_ID = "sovellus-id"
private const val HEADER_APP_OPERATION = "sovellus-toiminto"
private const val HEADER_APP_MODIFIED_AT = "sovellus-muokattu"
private const val HEADER_APP_MUTATION_ID = "sovellus-muokkaus-id"
private const val HEADER_PERFORMANCE = "esitys:"
private const val HEADER_DATE = "pvm:"
private const val HEADER_RESERVATION_TOTAL = "varaukset yhteensä"
private const val APP_OPERATION_ADD = "Lisäys"
private const val APP_OPERATION_DELETE = "Poisto"
private val VALID_APPLICATION_OPERATIONS = setOf(
    APP_OPERATION_ADD.normalizedHeader(),
    APP_OPERATION_DELETE.normalizedHeader()
)
private val APPLICATION_MUTATION_METADATA_HEADERS = listOf(
    HEADER_APP_OPERATION,
    HEADER_APP_MODIFIED_AT,
    HEADER_APP_MUTATION_ID
)
private const val LEGACY_DOOR_SALE_SHEET_LABEL = "Ovelta"
private const val LOCK_HEADER_PERFORMANCE_ID = "performance_id"
private const val LOCK_HEADER_UUID = "lock_uuid"
private const val LOCK_HEADER_LOCKED_AT = "locked_at"
private const val LOCK_HEADER_EXPIRES_AT = "expires_at"
private const val LOCK_HEADER_DEVICE_LABEL = "device_label"
private val REQUIRED_LOCK_HEADERS = listOf(
    LOCK_HEADER_PERFORMANCE_ID,
    LOCK_HEADER_UUID,
    LOCK_HEADER_LOCKED_AT,
    LOCK_HEADER_EXPIRES_AT,
    LOCK_HEADER_DEVICE_LABEL
)

private val ticketHeaders = mapOf(
    TicketType.BASIC to "perus 22 €",
    TicketType.DISCOUNT to "alennus 13 €",
    TicketType.THEATRE_INDUSTRY to "teatteriala 10 €",
    TicketType.MEMBER to "jäsen 5 €",
    TicketType.GROUP_BASIC to "ryhmä perus 20 €",
    TicketType.GROUP_DISCOUNT to "ryhmä alennus 12 €",
    TicketType.KAIKUKORTTI to "kaikukortti",
    TicketType.FREE_TICKET to "vapaalippu"
)

private val paymentHeaders = mapOf(
    PaymentMethod.CARD to "kortti",
    PaymentMethod.CASH to "käteinen",
    PaymentMethod.EPASSI to "epassi",
    PaymentMethod.LIPPUAGENTTI to "lippuagentti"
)
