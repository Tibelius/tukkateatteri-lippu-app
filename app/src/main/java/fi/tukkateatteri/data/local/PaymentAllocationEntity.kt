package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.PaymentMethod

@Entity(
    tableName = "payment_allocations",
    foreignKeys = [
        ForeignKey(
            entity = TicketSaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ticket_sale_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("ticket_sale_id")]
)
data class PaymentAllocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "ticket_sale_id")
    val ticketSaleId: Long,
    @ColumnInfo(name = "payment_method")
    val paymentMethod: PaymentMethod,
    @ColumnInfo(name = "amount_cents")
    val amountCents: Int
)
