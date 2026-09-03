package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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

class GoogleSheetsClient {
    suspend fun loadTabs(spreadsheetUrl: String, accessToken: String): List<GoogleSheetTab> = withContext(Dispatchers.IO) {
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
        return@withContext titles.map { title ->
            GoogleSheetTab(title = title, rows = loadValues(spreadsheetId, title, accessToken))
        }
    }

    suspend fun exportRows(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        rows: List<ReservationSpreadsheetRow>
    ) = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        val existingRows = loadValues(spreadsheetId, sheetTitle, accessToken)
        val headerRowIndex = existingRows.indexOfFirst { row -> row.any { cell -> cell.normalizedHeader() == HEADER_LAST_NAME } }
        require(headerRowIndex >= 0) { "Välilehdeltä ei löytynyt Sukunimi-saraketta." }
        val headers = existingRows[headerRowIndex].mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
        val existingByName = existingRows.drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
            val key = row.valueAt(headers[HEADER_LAST_NAME]).normalizedIdentity() to row.valueAt(headers[HEADER_FIRST_NAME]).normalizedIdentity()
            key.takeIf { it.first.isNotBlank() || it.second.isNotBlank() }?.let { it to headerRowIndex + index + 2 }
        }.toMap()
        rows.forEach { row ->
            val values = row.toSheetValues(headers)
            val rowNumber = existingByName[row.lastName.normalizedIdentity() to row.firstName.normalizedIdentity()]
            if (rowNumber != null) {
                putValues(spreadsheetId, "$sheetTitle!A$rowNumber", values, accessToken)
            } else {
                appendValues(spreadsheetId, sheetTitle, values, accessToken)
            }
        }
    }

    suspend fun loadImportCandidates(spreadsheetUrl: String, accessToken: String): List<GoogleSheetImportCandidate> =
        loadTabs(spreadsheetUrl, accessToken)
            .mapNotNull(GoogleSheetTab::toImportCandidateOrNull)
            .sortedWith(compareBy<GoogleSheetImportCandidate> { it.sortDate ?: LocalDate.MAX }.thenBy { it.date })

    suspend fun importTab(
        spreadsheetUrl: String,
        accessToken: String,
        sheetTitle: String
    ): List<ReservationSpreadsheetRow> {
        val tab = loadTabs(spreadsheetUrl, accessToken).firstOrNull { it.title == sheetTitle }
            ?: throw IllegalArgumentException("Valittua välilehteä ei löytynyt.")
        val candidate = tab.toImportCandidateOrNull()
            ?: throw IllegalArgumentException("Välilehdeltä puuttuu Esitys: tai Pvm: -tieto.")
        return tab.toReservationSpreadsheetRows(candidate)
    }

    private fun loadValues(spreadsheetId: String, title: String, accessToken: String): List<List<String>> {
        val range = URLEncoder.encode("$title!A:Z", Charsets.UTF_8.name())
        val response = getJson("$API_BASE/spreadsheets/$spreadsheetId/values/$range", accessToken)
        val values = response.optJSONArray("values") ?: return emptyList()
        return buildList {
            for (rowIndex in 0 until values.length()) {
                val row = values.getJSONArray(rowIndex)
                add(buildList {
                    for (cellIndex in 0 until row.length()) add(row.getString(cellIndex))
                })
            }
        }
    }

    private fun getJson(url: String, accessToken: String): JSONObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader().use { it.readText() }
            if (responseCode !in 200..299) {
                throw IllegalStateException("Google Sheets -pyyntö epäonnistui ($responseCode): $response")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    private fun putValues(spreadsheetId: String, range: String, values: List<String>, accessToken: String) {
        val encodedRange = URLEncoder.encode(range, Charsets.UTF_8.name())
        sendJson("$API_BASE/spreadsheets/$spreadsheetId/values/$encodedRange?valueInputOption=RAW", "PUT", values, accessToken)
    }

    private fun appendValues(spreadsheetId: String, sheetTitle: String, values: List<String>, accessToken: String) {
        val encodedRange = URLEncoder.encode("$sheetTitle!A:Z", Charsets.UTF_8.name())
        sendJson("$API_BASE/spreadsheets/$spreadsheetId/values/$encodedRange:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", "POST", values, accessToken)
    }

    private fun sendJson(url: String, method: String, values: List<String>, accessToken: String) {
        val requestBody = JSONObject().put("values", JSONArray().put(JSONArray(values))).toString().toByteArray()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(requestBody) }
            if (connection.responseCode !in 200..299) throw IllegalStateException("Google Sheets -pyyntö epäonnistui: ${connection.responseCode}")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val API_BASE = "https://sheets.googleapis.com/v4"
    }
}

fun GoogleSheetTab.toImportCandidateOrNull(): GoogleSheetImportCandidate? {
    val performanceName = valueRightOfLabel(HEADER_PERFORMANCE) ?: return null
    val date = valueRightOfLabel(HEADER_DATE) ?: return null
    return GoogleSheetImportCandidate(
        sheetTitle = title,
        performanceName = performanceName,
        date = date,
        sortDate = date.toLocalDateOrNull()
    )
}

fun GoogleSheetTab.toReservationSpreadsheetRows(candidate: GoogleSheetImportCandidate): List<ReservationSpreadsheetRow> {
    val headerRowIndex = rows.indexOfFirst { row -> row.any { cell -> cell.normalizedHeader() == HEADER_LAST_NAME } }
    if (headerRowIndex < 0) return emptyList()
    val headerIndexes = rows[headerRowIndex].mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    return rows.drop(headerRowIndex + 1).takeWhile { row ->
        row.valueAt(headerIndexes[HEADER_LAST_NAME]).isNotBlank() || row.valueAt(headerIndexes[HEADER_FIRST_NAME]).isNotBlank()
    }.mapNotNull { row ->
        val lastName = row.valueAt(headerIndexes[HEADER_LAST_NAME]).trim()
        val firstName = row.valueAt(headerIndexes[HEADER_FIRST_NAME]).trim()
        if (lastName.isBlank() && firstName.isBlank()) return@mapNotNull null
        ReservationSpreadsheetRow(
            lastName = lastName,
            firstName = firstName,
            contact = row.valueAt(headerIndexes[HEADER_CONTACT]).trim(),
            reservedSeatCount = row.valueAt(headerIndexes[HEADER_RESERVED_COUNT]).toTicketCount().coerceAtLeast(1),
            arrivalCount = row.valueAt(headerIndexes[HEADER_ARRIVAL_COUNT]).toTicketCount(),
            reservedTicketCounts = ticketHeaders.mapNotNull { (ticketType, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { ticketType to it }
            }.toMap(),
            paymentTicketCounts = paymentHeaders.mapNotNull { (paymentMethod, header) ->
                row.valueAt(headerIndexes[header]).toTicketCount().takeIf { it > 0 }?.let { paymentMethod to it }
            }.toMap(),
            notes = row.valueAt(headerIndexes[HEADER_NOTES]).trim(),
            sourceIdentity = "${candidate.performanceName.normalizedIdentity()}|${candidate.date.normalizedIdentity()}|${lastName.normalizedIdentity()}|${firstName.normalizedIdentity()}"
        )
    }
}

private fun GoogleSheetTab.valueRightOfLabel(label: String): String? = rows.firstNotNullOfOrNull { row ->
    row.indexOfFirst { value -> value.normalizedHeader() == label }
        .takeIf { index -> index >= 0 }
        ?.let { index -> row.getOrNull(index + 1)?.trim() }
        ?.takeIf(String::isNotBlank)
}

private fun ReservationSpreadsheetRow.toSheetValues(headers: Map<String, Int>): List<String> {
    val values = MutableList((headers.values.maxOrNull() ?: 0) + 1) { "" }
    fun set(header: String, value: String) { headers[header]?.let { values[it] = value } }
    set(HEADER_LAST_NAME, lastName)
    set(HEADER_FIRST_NAME, firstName)
    set(HEADER_CONTACT, contact)
    set(HEADER_RESERVED_COUNT, reservedSeatCount.toString())
    set(HEADER_ARRIVAL_COUNT, arrivalCount.toString())
    ticketHeaders.forEach { (type, header) -> set(header, reservedTicketCounts[type]?.toString().orEmpty()) }
    paymentHeaders.forEach { (method, header) -> set(header, paymentTicketCounts[method]?.toString().orEmpty()) }
    set(HEADER_NOTES, notes)
    return values
}

private fun String.toSpreadsheetId(): String {
    val match = SPREADSHEET_ID_REGEX.find(this)
        ?: throw IllegalArgumentException("Google Sheets -osoite ei ole kelvollinen.")
    return match.groupValues[1]
}

private fun String.normalizedHeader(): String = lowercase()
    .replace(Regex("\\s+"), " ")
    .replace("*", "")
    .trim()

private fun String.normalizedIdentity(): String = trim().lowercase().replace(Regex("\\s+"), " ")

private fun String.toLocalDateOrNull(): LocalDate? = try {
    LocalDate.parse(trim().take(10), DateTimeFormatter.ofPattern("d.M.uuuu"))
} catch (_: DateTimeParseException) {
    null
}

private fun List<String>.valueAt(index: Int?): String = index?.let { getOrNull(it) }.orEmpty()

private fun String.toTicketCount(): Int = trim().toIntOrNull()
    ?: if (trim().lowercase() in setOf("x", "✓", "k")) 1 else 0

private val SPREADSHEET_ID_REGEX = Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)")
private const val HEADER_LAST_NAME = "sukunimi"
private const val HEADER_FIRST_NAME = "etunimi"
private const val HEADER_CONTACT = "yhteystiedot"
private const val HEADER_RESERVED_COUNT = "varatut liput kpl"
private const val HEADER_ARRIVAL_COUNT = "saapunut esitykseen eli lunastettujen lippujen lukumäärä"
private const val HEADER_NOTES = "huom! (merkitse tähän esim. vapaalipun peruste, joka voi olla työryhmävapaalippu, kaikukortti, kutsu tms. sekä muut huomioitavat asiat)"
private const val HEADER_PERFORMANCE = "esitys:"
private const val HEADER_DATE = "pvm:"

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
