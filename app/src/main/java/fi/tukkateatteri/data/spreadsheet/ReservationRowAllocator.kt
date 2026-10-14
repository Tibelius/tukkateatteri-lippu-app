package fi.tukkateatteri.data.spreadsheet

/** Reuses empty data rows, then inserts before the totals or performance metadata. */
internal class ReservationRowAllocator(
    rows: List<List<String>>,
    headerRowIndex: Int,
    rowIdsByRowNumber: Map<Int, String>
) {
    private val firstDataIndex = headerRowIndex + 1
    private val footerIndex = rows.withIndex().firstOrNull { (index, cells) ->
        index >= firstDataIndex && cells.isReservationFooterRow()
    }?.index ?: rows.size
    private val emptyRowNumbers = ArrayDeque(
        (firstDataIndex until footerIndex).filter { index ->
            index + 1 !in rowIdsByRowNumber && rows[index].all { cell ->
                val value = cell.trimSpreadsheetWhitespace()
                value.isBlank() || value.equals("false", ignoreCase = true)
            }
        }.map { it + 1 }
    )
    private var nextInsertedRow = footerIndex + 1

    fun allocate(): ReservationRowPlacement = if (emptyRowNumbers.isNotEmpty()) {
        ReservationRowPlacement(emptyRowNumbers.removeFirst(), insert = false)
    } else {
        ReservationRowPlacement(nextInsertedRow++, insert = true)
    }
}

internal data class ReservationRowPlacement(val rowNumber: Int, val insert: Boolean)
