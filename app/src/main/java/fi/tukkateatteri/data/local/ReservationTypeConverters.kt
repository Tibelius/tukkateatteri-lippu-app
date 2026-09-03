package fi.tukkateatteri.data.local

import androidx.room.TypeConverter
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod
import fi.tukkateatteri.data.TicketType
import fi.tukkateatteri.data.TicketSaleOrigin

class ReservationTypeConverters {
    @TypeConverter
    fun admissionTypeFromStorage(value: String?): AdmissionType =
        value?.let { storedValue -> AdmissionType.entries.find { it.name == storedValue } }
            ?: AdmissionType.RESERVATION

    @TypeConverter
    fun admissionTypeToStorage(value: AdmissionType): String = value.name

    @TypeConverter
    fun paymentMethodFromStorage(value: String?): PaymentMethod? =
        value?.let { storedValue -> PaymentMethod.entries.find { it.name == storedValue } }

    @TypeConverter
    fun paymentMethodToStorage(value: PaymentMethod?): String? = value?.name

    @TypeConverter
    fun ticketTypeFromStorage(value: String?): TicketType =
        value?.let { storedValue -> TicketType.entries.find { it.name == storedValue } }
            ?: TicketType.UNSPECIFIED

    @TypeConverter
    fun ticketTypeToStorage(value: TicketType): String = value.name

    @TypeConverter
    fun ticketSaleOriginFromStorage(value: String?): TicketSaleOrigin =
        value?.let { storedValue -> TicketSaleOrigin.entries.find { it.name == storedValue } }
            ?: TicketSaleOrigin.MANUAL

    @TypeConverter
    fun ticketSaleOriginToStorage(value: TicketSaleOrigin): String = value.name
}
