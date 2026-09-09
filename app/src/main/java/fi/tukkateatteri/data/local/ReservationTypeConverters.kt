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
        value?.let { storedValue -> PaymentMethod.entries.find { entry -> entry.name == storedValue } }

    @TypeConverter
    fun paymentMethodToStorage(value: PaymentMethod?): String? = value?.name

    @TypeConverter
    fun ticketTypeFromStorage(value: String?): TicketType =
        value.toEnumOrDefault(TicketType.entries, TicketType.UNSPECIFIED)

    @TypeConverter
    fun ticketTypeToStorage(value: TicketType): String = value.name

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
}

private fun <T : Enum<T>> String?.toEnumOrDefault(entries: List<T>, default: T): T =
    this?.let { storedValue -> entries.find { entry -> entry.name == storedValue } } ?: default
