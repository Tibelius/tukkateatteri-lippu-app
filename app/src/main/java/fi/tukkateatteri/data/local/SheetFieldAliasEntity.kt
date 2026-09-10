package fi.tukkateatteri.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.spreadsheet.SheetColumnSchema
import fi.tukkateatteri.data.spreadsheet.SheetFieldClassification
import fi.tukkateatteri.data.spreadsheet.SheetFieldMapping
import fi.tukkateatteri.data.spreadsheet.StoredSheetAlias
import fi.tukkateatteri.data.spreadsheet.normalizedHeader

@Entity(tableName = "sheet_field_aliases")
data class SheetFieldAliasEntity(
    @PrimaryKey val normalizedAlias: String,
    val kind: SheetFieldKind,
    val label: String,
    val allowsSplitPayment: Boolean
) {
    internal fun toStoredAlias(): StoredSheetAlias = StoredSheetAlias(
        kind = kind.name,
        label = label,
        priceCents = null,
        allowsSplitPayment = allowsSplitPayment
    )
}

fun SheetColumnSchema.toAliasEntities(): List<SheetFieldAliasEntity> =
    ticketHeaders.map { (type, alias) ->
        SheetFieldAliasEntity(alias, SheetFieldKind.TICKET, type.label, false)
    } + paymentHeaders.map { (method, alias) ->
        SheetFieldAliasEntity(alias, SheetFieldKind.PAYMENT, method.label, method.allowsSplitPayment)
    }

fun SheetFieldMapping.toAliasEntity(): SheetFieldAliasEntity = SheetFieldAliasEntity(
    normalizedAlias = header.normalizedHeader(),
    kind = when (classification) {
        SheetFieldClassification.TICKET -> SheetFieldKind.TICKET
        SheetFieldClassification.PAYMENT -> SheetFieldKind.PAYMENT
        SheetFieldClassification.IGNORE -> SheetFieldKind.IGNORE
    },
    label = header.trim(),
    allowsSplitPayment = true
)
