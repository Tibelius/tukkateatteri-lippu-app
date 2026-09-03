package fi.tukkateatteri.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fi.tukkateatteri.data.Performance

@Entity(
    tableName = "performances",
    indices = [Index(value = ["act_name", "performance_date"], unique = true)]
)
data class PerformanceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "act_name")
    val actName: String,
    @ColumnInfo(name = "performance_date")
    val date: String,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false
)

fun PerformanceEntity.toPerformance() = Performance(
    id = id,
    actName = actName,
    date = date,
    isActive = isActive
)
