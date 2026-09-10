package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.spreadsheet.SheetColumnSchema

enum class SheetFieldKind { TICKET, PAYMENT, IGNORE }

@Entity(
    tableName = "sheet_field_definitions",
    primaryKeys = ["spreadsheet_url", "normalized_header"]
)
data class SheetFieldDefinitionEntity(
    @ColumnInfo(name = "spreadsheet_url") val spreadsheetUrl: String,
    @ColumnInfo(name = "normalized_header") val normalizedHeader: String,
    val kind: SheetFieldKind,
    val identifier: String,
    val label: String,
    @ColumnInfo(name = "price_cents") val priceCents: Int?,
    @ColumnInfo(name = "allows_split_payment") val allowsSplitPayment: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val active: Boolean
) {
    fun toTicketType(): TicketType? = takeIf { kind == SheetFieldKind.TICKET }?.let {
        TicketType(identifier, label, requireNotNull(priceCents), sortOrder)
    }

    fun toPaymentMethod(): PaymentMethod? = takeIf { kind == SheetFieldKind.PAYMENT }?.let {
        PaymentMethod(identifier, label, allowsSplitPayment, sortOrder)
    }
}

fun SheetColumnSchema.toEntities(spreadsheetUrl: String): List<SheetFieldDefinitionEntity> =
    ticketHeaders.map { (type, header) ->
        SheetFieldDefinitionEntity(
            spreadsheetUrl = spreadsheetUrl,
            normalizedHeader = header,
            kind = SheetFieldKind.TICKET,
            identifier = type.name,
            label = type.label,
            priceCents = type.defaultPriceCents,
            allowsSplitPayment = false,
            sortOrder = type.sortOrder,
            active = true
        )
    } + paymentHeaders.map { (method, header) ->
        SheetFieldDefinitionEntity(
            spreadsheetUrl = spreadsheetUrl,
            normalizedHeader = header,
            kind = SheetFieldKind.PAYMENT,
            identifier = method.name,
            label = method.label,
            priceCents = null,
            allowsSplitPayment = method.allowsSplitPayment,
            sortOrder = method.sortOrder,
            active = true
        )
    }
