package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.TicketType

@Entity(
    tableName = "ticket_sales",
    foreignKeys = [
        ForeignKey(
            entity = ReservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["reservation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("reservation_id")]
)
data class TicketSaleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "reservation_id")
    val reservationId: Long,
    @ColumnInfo(name = "ticket_type")
    val ticketType: TicketType,
    val quantity: Int,
    @ColumnInfo(name = "unit_price_cents")
    val unitPriceCents: Int
)
