package fi.tukkateatteri.data.spreadsheet

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal data class SpreadsheetStructure(
    val sheetsByTitle: Map<String, SheetProperties>,
    val rowIdsBySheetId: Map<Int, Map<Int, String>>
)

internal data class SheetProperties(
    val id: Int,
    val title: String,
    val hidden: Boolean
)

internal fun JSONObject.toSpreadsheetStructure(): SpreadsheetStructure {
    val sheets = getJSONArray("sheets").toObjects().map { sheet ->
        val properties = sheet.getJSONObject("properties")
        SheetProperties(
            id = properties.getInt("sheetId"),
            title = properties.getString("title"),
            hidden = properties.optBoolean("hidden")
        )
    }
    val rowIds = optJSONArray("developerMetadata").toObjects()
        .mapNotNull(JSONObject::toReservationRowMetadataOrNull)
        .groupBy(ReservationRowMetadata::sheetId)
        .mapValues { (_, metadata) ->
            metadata.associate { it.rowNumber to it.rowId }
        }
    return SpreadsheetStructure(
        sheetsByTitle = sheets.associateBy(SheetProperties::title),
        rowIdsBySheetId = rowIds
    )
}

private data class ReservationRowMetadata(
    val sheetId: Int,
    val rowNumber: Int,
    val rowId: String
)

private fun JSONObject.toReservationRowMetadataOrNull(): ReservationRowMetadata? {
    if (optString("metadataKey") != ROW_ID_METADATA_KEY) return null
    val rowId = optString("metadataValue").takeIf(String::isUuid) ?: return null
    val range = optJSONObject("location")?.optJSONObject("dimensionRange") ?: return null
    if (range.optString("dimension") != SHEET_DIMENSION_ROWS) return null
    val startIndex = range.optInt("startIndex", -1)
    val endIndex = range.optInt("endIndex", -1)
    if (startIndex < 0 || endIndex != startIndex + 1) return null
    return ReservationRowMetadata(
        sheetId = range.getInt("sheetId"),
        rowNumber = startIndex + 1,
        rowId = rowId
    )
}

data class ApplicationRowState(
    val sheetId: Int,
    val rowId: String,
    val contentHash: String,
    val modifiedAt: Instant?,
    val operation: String,
    val mutationId: String,
    val rowNumber: Int
) {
    val isValid: Boolean
        get() = sheetId >= 0 &&
            rowId.isUuid() &&
            contentHash.matches(SHA_256_PATTERN) &&
            modifiedAt != null &&
            operation.normalizedHeader() in VALID_APPLICATION_OPERATIONS &&
            mutationId.isUuid()
}

internal data class ApplicationRowStateTable(
    val headers: Map<String, Int>,
    val rows: List<ApplicationRowState>
) {
    fun stateFor(sheetId: Int, rowId: String): ApplicationRowState? = rows.lastOrNull { state ->
        state.sheetId == sheetId && state.rowId == rowId
    }

    fun valuesFor(
        rowNumber: Int,
        sheetId: Int,
        rowId: String,
        contentHash: String,
        operation: String,
        modifiedAt: Instant = Instant.now(),
        mutationId: String = UUID.randomUUID().toString()
    ): List<SheetCellValue> = buildList {
        fun set(header: String, value: Any) {
            headers[header]?.let { columnIndex ->
                add(SheetCellValue(sheetCellRange(APPLICATION_SHEET_TITLE, columnIndex, rowNumber), value))
            }
        }
        set(ROW_STATE_HEADER_SHEET_ID, sheetId)
        set(ROW_STATE_HEADER_ROW_ID, rowId)
        set(ROW_STATE_HEADER_CONTENT_HASH, contentHash)
        set(ROW_STATE_HEADER_MODIFIED_AT, modifiedAt.toString())
        set(ROW_STATE_HEADER_OPERATION, operation)
        set(ROW_STATE_HEADER_MUTATION_ID, mutationId)
    }

    fun clearValuesFor(rowNumber: Int): List<SheetCellValue> = REQUIRED_ROW_STATE_HEADERS.mapNotNull { header ->
        headers[header]?.let { columnIndex ->
            SheetCellValue(sheetCellRange(APPLICATION_SHEET_TITLE, columnIndex, rowNumber), "")
        }
    }
}

internal fun List<List<String>>.toApplicationRowStateTable(): ApplicationRowStateTable {
    val headerRowIndex = indexOfFirst { row ->
        row.valueAt(ROW_STATE_TABLE_START_COLUMN).normalizedHeader() == ROW_STATE_HEADER_SHEET_ID
    }
    require(headerRowIndex >= 0) { "Sovellus-välilehdeltä puuttuu rivitietojen otsikkorivi." }
    val headers = get(headerRowIndex).mapIndexed { index, header -> header.normalizedHeader() to index }.toMap()
    require(REQUIRED_ROW_STATE_HEADERS.all(headers::containsKey)) {
        "Sovellus-välilehden rivitietojen otsikot eivät vastaa sovittua muotoa."
    }
    val states = drop(headerRowIndex + 1).mapIndexedNotNull { index, row ->
        val rowId = row.valueAt(headers[ROW_STATE_HEADER_ROW_ID]).trimSpreadsheetWhitespace()
        if (rowId.isBlank()) return@mapIndexedNotNull null
        ApplicationRowState(
            sheetId = row.valueAt(headers[ROW_STATE_HEADER_SHEET_ID]).toIntOrNull() ?: -1,
            rowId = rowId,
            contentHash = row.valueAt(headers[ROW_STATE_HEADER_CONTENT_HASH]).trimSpreadsheetWhitespace(),
            modifiedAt = runCatching {
                Instant.parse(row.valueAt(headers[ROW_STATE_HEADER_MODIFIED_AT]).trimSpreadsheetWhitespace())
            }.getOrNull(),
            operation = row.valueAt(headers[ROW_STATE_HEADER_OPERATION]).trimSpreadsheetWhitespace(),
            mutationId = row.valueAt(headers[ROW_STATE_HEADER_MUTATION_ID]).trimSpreadsheetWhitespace(),
            rowNumber = headerRowIndex + index + 2
        )
    }
    return ApplicationRowStateTable(headers, states)
}

internal fun ReservationSpreadsheetRow.sheetContentHash(): String {
    val canonicalContent = copy(sourceIdentity = "", sheetRowId = "", sourceRowNumber = null).toSnapshotJson()
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalContent.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun JSONArray?.toObjects(): List<JSONObject> {
    val array = this ?: return emptyList()
    return List(array.length()) { index -> array.getJSONObject(index) }
}

internal const val ROW_ID_METADATA_KEY = "tukkateatteri_row_id"
internal const val SHEET_DIMENSION_ROWS = "ROWS"
internal const val ROW_STATE_TABLE_START_COLUMN = 13
internal const val ROW_STATE_HEADER_SHEET_ID = "sheet_id"
internal const val ROW_STATE_HEADER_ROW_ID = "row_uuid"
internal const val ROW_STATE_HEADER_CONTENT_HASH = "content_hash"
internal const val ROW_STATE_HEADER_MODIFIED_AT = "modified_at"
internal const val ROW_STATE_HEADER_OPERATION = "operation"
internal const val ROW_STATE_HEADER_MUTATION_ID = "mutation_id"
internal val REQUIRED_ROW_STATE_HEADERS = listOf(
    ROW_STATE_HEADER_SHEET_ID,
    ROW_STATE_HEADER_ROW_ID,
    ROW_STATE_HEADER_CONTENT_HASH,
    ROW_STATE_HEADER_MODIFIED_AT,
    ROW_STATE_HEADER_OPERATION,
    ROW_STATE_HEADER_MUTATION_ID
)

internal const val APP_OPERATION_ADD = "Lisäys"
internal const val APP_OPERATION_UPDATE = "Muutos"
internal const val APP_OPERATION_DELETE = "Poisto"
internal val VALID_APPLICATION_OPERATIONS = setOf(
    APP_OPERATION_ADD.normalizedHeader(),
    APP_OPERATION_UPDATE.normalizedHeader(),
    APP_OPERATION_DELETE.normalizedHeader()
)

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
