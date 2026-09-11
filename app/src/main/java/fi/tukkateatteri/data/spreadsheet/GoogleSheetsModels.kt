package fi.tukkateatteri.data.spreadsheet

import java.io.IOException
import java.time.LocalDate

data class GoogleSheetTab(
    val title: String,
    val rows: List<List<String>>,
    val sheetId: Int = -1,
    val rowIdsByRowNumber: Map<Int, String> = emptyMap(),
    val applicationRowStates: Map<String, ApplicationRowState> = emptyMap()
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
