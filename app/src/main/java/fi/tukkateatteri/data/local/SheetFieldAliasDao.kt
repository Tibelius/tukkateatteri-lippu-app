package fi.tukkateatteri.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SheetFieldAliasDao {
    @Query("SELECT * FROM sheet_field_aliases")
    suspend fun getAll(): List<SheetFieldAliasEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<SheetFieldAliasEntity>)
}
