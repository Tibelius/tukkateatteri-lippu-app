package fi.tukkateatteri.data.local

import androidx.room.TypeConverter
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.TicketSaleOrigin
import fi.tukkateatteri.data.ReservationSyncState

class ReservationTypeConverters {
    @TypeConverter
    fun admissionTypeFromStorage(value: String?): AdmissionType =
        value.toEnumOrDefault(AdmissionType.entries, AdmissionType.RESERVATION)

    @TypeConverter
    fun admissionTypeToStorage(value: AdmissionType): String = value.name

    @TypeConverter
    fun paymentMethodFromStorage(value: String?): PaymentMethod? =
        value?.let(ConfigValueCodec::decodePaymentMethod)

    @TypeConverter
    fun paymentMethodToStorage(value: PaymentMethod?): String? = value?.let(ConfigValueCodec::encode)

    @TypeConverter
    fun ticketTypeFromStorage(value: String?): TicketType =
        value?.let(ConfigValueCodec::decodeTicketType) ?: TicketType.UNSPECIFIED

    @TypeConverter
    fun ticketTypeToStorage(value: TicketType): String = ConfigValueCodec.encode(value)

    @TypeConverter
    fun ticketSaleOriginFromStorage(value: String?): TicketSaleOrigin =
        value.toEnumOrDefault(TicketSaleOrigin.entries, TicketSaleOrigin.MANUAL)

    @TypeConverter
    fun ticketSaleOriginToStorage(value: TicketSaleOrigin): String = value.name

    @TypeConverter
    fun reservationSyncStateFromStorage(value: String?): ReservationSyncState =
        value.toEnumOrDefault(ReservationSyncState.entries, ReservationSyncState.SYNCED)

    @TypeConverter
    fun reservationSyncStateToStorage(value: ReservationSyncState): String = value.name

    @TypeConverter
    fun pendingSheetOperationFromStorage(value: String?): PendingSheetOperation =
        value.toEnumOrDefault(PendingSheetOperation.entries, PendingSheetOperation.UPSERT)

    @TypeConverter
    fun pendingSheetOperationToStorage(value: PendingSheetOperation): String = value.name

    @TypeConverter
    fun pendingSheetChangeStatusFromStorage(value: String?): PendingSheetChangeStatus =
        value.toEnumOrDefault(PendingSheetChangeStatus.entries, PendingSheetChangeStatus.PENDING)

    @TypeConverter
    fun pendingSheetChangeStatusToStorage(value: PendingSheetChangeStatus): String = value.name

    @TypeConverter
    fun sheetFieldKindFromStorage(value: String?): SheetFieldKind =
        value.toEnumOrDefault(SheetFieldKind.entries, SheetFieldKind.TICKET)

    @TypeConverter
    fun sheetFieldKindToStorage(value: SheetFieldKind): String = value.name
}

internal object ConfigValueCodec {
    private const val DYNAMIC_PREFIX = "v1|"

    fun encode(value: TicketType): String = buildString {
        append(DYNAMIC_PREFIX)
        append(value.defaultPriceCents).append('|')
        append(value.sortOrder).append('|')
        appendLengthPrefixed(value.name)
        append(value.label)
    }

    fun encode(value: PaymentMethod): String = buildString {
        append(DYNAMIC_PREFIX)
        append(if (value.allowsSplitPayment) 1 else 0).append('|')
        append(value.sortOrder).append('|')
        appendLengthPrefixed(value.name)
        append(value.label)
    }

    fun decodeTicketType(value: String): TicketType {
        if (!value.startsWith(DYNAMIC_PREFIX)) {
            return TicketType.entries.firstOrNull { it.name == value } ?: TicketType.UNSPECIFIED
        }
        return runCatching {
            val fields = DynamicFields(value)
            TicketType(
                name = fields.name,
                label = fields.label,
                defaultPriceCents = fields.first.toInt(),
                sortOrder = fields.second.toInt()
            )
        }.getOrDefault(TicketType.UNSPECIFIED)
    }

    fun decodePaymentMethod(value: String): PaymentMethod? {
        if (!value.startsWith(DYNAMIC_PREFIX)) {
            return PaymentMethod.entries.firstOrNull { it.name == value }
        }
        return runCatching {
            val fields = DynamicFields(value)
            PaymentMethod(
                name = fields.name,
                label = fields.label,
                allowsSplitPayment = fields.first == "1",
                sortOrder = fields.second.toInt()
            )
        }.getOrNull()
    }

    private fun StringBuilder.appendLengthPrefixed(value: String) {
        append(value.length).append(':').append(value)
    }

    private class DynamicFields(encoded: String) {
        val first: String
        val second: String
        val name: String
        val label: String

        init {
            val payload = encoded.removePrefix(DYNAMIC_PREFIX)
            val firstSeparator = payload.indexOf('|')
            val secondSeparator = payload.indexOf('|', firstSeparator + 1)
            val lengthSeparator = payload.indexOf(':', secondSeparator + 1)
            require(firstSeparator > 0 && secondSeparator > firstSeparator && lengthSeparator > secondSeparator)
            first = payload.substring(0, firstSeparator)
            second = payload.substring(firstSeparator + 1, secondSeparator)
            val nameLength = payload.substring(secondSeparator + 1, lengthSeparator).toInt()
            val nameStart = lengthSeparator + 1
            val nameEnd = nameStart + nameLength
            require(nameEnd <= payload.length)
            name = payload.substring(nameStart, nameEnd)
            label = payload.substring(nameEnd)
        }
    }
}

private fun <T : Enum<T>> String?.toEnumOrDefault(entries: List<T>, default: T): T =
    this?.let { storedValue -> entries.find { entry -> entry.name == storedValue } } ?: default
