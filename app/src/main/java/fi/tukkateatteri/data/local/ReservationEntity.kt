package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.PaymentMethod

@Entity(
    tableName = "reservations",
    foreignKeys = [
        ForeignKey(
            entity = PerformanceEntity::class,
            parentColumns = ["id"],
            childColumns = ["performance_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("source_identity"), Index("performance_id")]
)
data class ReservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "performance_id")
    val performanceId: Long,
    @ColumnInfo(name = "last_name")
    val lastName: String,
    @ColumnInfo(name = "first_name")
    val firstName: String,
    val contact: String,
    @ColumnInfo(name = "seat_count")
    val seatCount: Int,
    val notes: String = "",
    @ColumnInfo(name = "source_identity")
    val sourceIdentity: String = "",
    @ColumnInfo(name = "admission_type")
    val admissionType: AdmissionType = AdmissionType.RESERVATION,
    @ColumnInfo(name = "arrival_count")
    val arrivalCount: Int = 0,
    @ColumnInfo(name = "is_present")
    val isPresent: Boolean = false,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: PaymentMethod? = null
)
