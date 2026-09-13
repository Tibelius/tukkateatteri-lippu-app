package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import fi.tukkateatteri.data.TicketType

@Entity(
    tableName = "reserved_ticket_allocations",
    primaryKeys = ["reservation_id", "ticket_type"],
    foreignKeys = [
        ForeignKey(
            entity = ReservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["reservation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReservedTicketAllocationEntity(
    @ColumnInfo(name = "reservation_id")
    val reservationId: Long,
    @ColumnInfo(name = "ticket_type")
    val ticketType: TicketType,
    val quantity: Int
)
