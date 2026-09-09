package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A local-only outbox entry. Google Sheets never receives this bookkeeping data.
 *
 * The snapshots represent the row before and after the local change. They make it possible to
 * refuse an export when somebody edited the same row directly in the Sheet meanwhile.
 */
@Entity(
    tableName = "pending_sheet_changes",
    foreignKeys = [
        ForeignKey(
            entity = ReservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["reservation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["performance_id"]), Index(value = ["reservation_id"], unique = true)]
)
data class PendingSheetChangeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "reservation_id")
    val reservationId: Long,
    @ColumnInfo(name = "performance_id")
    val performanceId: Long,
    val operation: PendingSheetOperation,
    @ColumnInfo(name = "base_row_json")
    val baseRowJson: String?,
    @ColumnInfo(name = "desired_row_json")
    val desiredRowJson: String?,
    val status: PendingSheetChangeStatus = PendingSheetChangeStatus.PENDING,
    @ColumnInfo(name = "last_error")
    val lastError: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

enum class PendingSheetOperation { UPSERT, DELETE }

enum class PendingSheetChangeStatus { PENDING, CONFLICT }
