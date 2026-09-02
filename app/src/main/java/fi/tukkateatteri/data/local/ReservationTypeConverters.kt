package fi.tukkateatteri.data.local

import androidx.room.TypeConverter
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod

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
}
