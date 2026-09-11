package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal class GoogleSheetsApiClient {
    private val initializedSpreadsheets = mutableSetOf<String>()

    fun loadSpreadsheetMetadata(spreadsheetId: String, accessToken: String): JSONObject =
        getJson(
            url = "$API_BASE/spreadsheets/$spreadsheetId?includeGridData=false",
            accessToken = accessToken
        )

    @Synchronized
    fun ensureApplicationSheet(spreadsheetId: String, accessToken: String) {
        if (spreadsheetId in initializedSpreadsheets) return
        try {
            val metadata = loadSpreadsheetMetadata(spreadsheetId, accessToken)
            val sheets = metadata.getJSONArray("sheets")
            val exists = (0 until sheets.length()).any { index ->
                sheets.getJSONObject(index).getJSONObject("properties").getString("title") == APPLICATION_SHEET_TITLE
            }
            if (!exists) {
                AppLog.info(LOG_COMPONENT) { "Creating app-managed spreadsheet tab '$APPLICATION_SHEET_TITLE'" }
                val request = JSONObject().put(
                    "requests",
                    JSONArray().put(
                        JSONObject().put(
                            "addSheet",
                            JSONObject().put("properties", JSONObject().put("title", APPLICATION_SHEET_TITLE))
                        )
                    )
                )
                sendJson("$API_BASE/spreadsheets/$spreadsheetId:batchUpdate", HTTP_POST, request, accessToken)
            }
            val headers = REQUIRED_ALIAS_HEADERS.mapIndexed { index, header ->
                SheetCellValue(sheetCellRange(APPLICATION_SHEET_TITLE, index, 1), header)
            } + REQUIRED_LOCK_HEADERS.mapIndexed { index, header ->
                SheetCellValue(sheetCellRange(APPLICATION_SHEET_TITLE, LOCK_TABLE_START_COLUMN + index, 1), header)
            }
            updateCells(spreadsheetId, accessToken, headers)
            initializedSpreadsheets += spreadsheetId
        } catch (exception: Exception) {
            initializedSpreadsheets -= spreadsheetId
            throw exception
        }
    }

    fun loadValues(spreadsheetId: String, title: String, accessToken: String): List<List<String>> {
        val range = URLEncoder.encode(valuesRange(title), Charsets.UTF_8.name())
        val response = getJson("$API_BASE/spreadsheets/$spreadsheetId/values/$range", accessToken)
        return response.optJSONArray("values").toRows()
    }

    fun loadValuesForTabs(
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
        sheetTitle.toQuotedSheetName()

    private fun getJson(url: String, accessToken: String): JSONObject {
        val operation = url.toApiOperation()
        val startedAt = System.nanoTime()
        AppLog.verbose(LOG_COMPONENT) { "Sending Google Sheets GET request; operation=$operation" }
        val connection = openConnection(url, accessToken)
        connection.requestMethod = HTTP_GET
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val responseCode = connection.responseCode
            val response = connection.responseText(responseCode)
            if (responseCode !in HTTP_SUCCESS_CODES) {
                throw GoogleSheetsRequestException(HTTP_GET, operation, responseCode)
            }
            return JSONObject(response).also {
                AppLog.verbose(LOG_COMPONENT) {
                    "Google Sheets GET request succeeded; operation=$operation, status=$responseCode, " +
                        "durationMs=${AppLog.elapsedMillis(startedAt)}"
                }
            }
        } catch (exception: GoogleSheetsRequestException) {
            AppLog.error(LOG_COMPONENT, exception) { "Google Sheets GET request failed; operation=$operation" }
            throw exception
        } catch (exception: IOException) {
            val wrapped = GoogleSheetsRequestException(HTTP_GET, operation, cause = exception)
            AppLog.error(LOG_COMPONENT, wrapped) { "Google Sheets GET request failed; operation=$operation" }
            throw wrapped
        } finally {
            connection.disconnect()
        }
    }

    fun ensureApplicationHeaders(
        spreadsheetId: String,
        sheetTitle: String,
        rows: List<List<String>>,
        headerRowIndex: Int,
        accessToken: String
    ): Map<String, Int> {
        val headers = rows[headerRowIndex].mapIndexed { index, header -> header.canonicalDataHeader() to index }.toMap().toMutableMap()
        if (HEADER_SHEET_ROW_ID !in headers) {
            val columnIndex = (headers.values.maxOrNull() ?: -1) + 1
            AppLog.info(LOG_COMPONENT) {
                "Adding missing Sovellus-ID header; tab=$sheetTitle, column=${columnIndex + 1}"
            }
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

    fun requireApplicationHeaders(headers: Map<String, Int>) {
        val missingHeaders = listOf(
            HEADER_SHEET_ROW_ID,
            HEADER_APP_OPERATION,
            HEADER_APP_MODIFIED_AT,
            HEADER_APP_MUTATION_ID
        ).filterNot(headers::containsKey)
        if (missingHeaders.isNotEmpty()) {
            AppLog.warning(LOG_COMPONENT) { "Required app columns are missing: ${missingHeaders.joinToString()}" }
        }
        require(missingHeaders.isEmpty()) {
            "Välilehdeltä puuttuvat sovellussarakkeet: ${missingHeaders.joinToString()}."
        }
    }

    fun firstAvailableReservationRow(rows: List<List<String>>, headerRowIndex: Int): Int {
        val firstDataRowIndex = headerRowIndex + 1
        val summaryRowIndex = rows.indexOfFirst { row ->
            row.any { cell -> cell.normalizedHeader().startsWith(HEADER_RESERVATION_TOTAL) }
        }.takeIf { it >= firstDataRowIndex } ?: rows.size
        val blankRowIndex = (firstDataRowIndex until summaryRowIndex).firstOrNull { rowIndex ->
            rows[rowIndex].valueAt(0).isBlank() && rows[rowIndex].valueAt(1).isBlank()
        }
        return (blankRowIndex ?: summaryRowIndex).plus(1).also { rowNumber ->
            AppLog.verbose(LOG_COMPONENT) {
                "Resolved first available reservation row=$rowNumber, summaryRow=${summaryRowIndex + 1}"
            }
        }
    }

    fun insertReservationRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        accessToken: String
    ) {
        AppLog.debug(LOG_COMPONENT) { "Inserting spreadsheet row; tab=$sheetTitle, row=$rowNumber" }
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

    fun deleteReservationRow(
        spreadsheetId: String,
        sheetTitle: String,
        rowNumber: Int,
        accessToken: String
    ) {
        AppLog.warning(LOG_COMPONENT) { "Hard-deleting spreadsheet row; tab=$sheetTitle, row=$rowNumber" }
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

    fun updateCells(
        spreadsheetId: String,
        accessToken: String,
        values: List<SheetCellValue>
    ) {
        if (values.isEmpty()) return
        AppLog.verbose(LOG_COMPONENT) { "Updating ${values.size} spreadsheet cells" }
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

    fun strikeThroughRow(
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

    fun clearStrikeThroughRow(
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

    fun setRowsStrikethrough(
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
        AppLog.debug(LOG_COMPONENT) {
            "Updating row strikethrough; tab=$sheetTitle, rows=${rowNumbers.distinct().size}, enabled=$enabled"
        }
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
            .firstOrNull { properties -> properties.getString("title") == sheetTitle }
            ?.getInt("sheetId")
            ?: throw IllegalArgumentException("Spreadsheet tab '$sheetTitle' no longer exists.")
    }

    private fun sendJson(url: String, method: String, request: JSONObject, accessToken: String) {
        val operation = url.toApiOperation()
        val startedAt = System.nanoTime()
        val requestBody = request.toString().toByteArray()
        AppLog.verbose(LOG_COMPONENT) {
            "Sending Google Sheets $method request; operation=$operation, payloadBytes=${requestBody.size}"
        }
        val connection = openConnection(url, accessToken)
        try {
            connection.requestMethod = method
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody) }
            val responseCode = connection.responseCode
            if (responseCode !in HTTP_SUCCESS_CODES) {
                connection.responseText(responseCode)
                throw GoogleSheetsRequestException(method, operation, responseCode)
            }
            AppLog.verbose(LOG_COMPONENT) {
                "Google Sheets $method request succeeded; operation=$operation, status=$responseCode, " +
                    "durationMs=${AppLog.elapsedMillis(startedAt)}"
            }
        } catch (exception: GoogleSheetsRequestException) {
            AppLog.error(LOG_COMPONENT, exception) { "Google Sheets $method request failed; operation=$operation" }
            throw exception
        } catch (exception: IOException) {
            val wrapped = GoogleSheetsRequestException(method, operation, cause = exception)
            AppLog.error(LOG_COMPONENT, wrapped) { "Google Sheets $method request failed; operation=$operation" }
            throw wrapped
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
    private companion object {
        const val LOG_COMPONENT = "Sheets"
        const val API_BASE = "https://sheets.googleapis.com/v4"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val HTTP_GET = "GET"
        const val HTTP_POST = "POST"
        const val SHEET_DIMENSION_ROWS = "ROWS"
        val HTTP_SUCCESS_CODES = 200..299
    }
}
