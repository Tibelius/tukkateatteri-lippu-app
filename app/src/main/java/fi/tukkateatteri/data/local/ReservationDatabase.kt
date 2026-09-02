package fi.tukkateatteri.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ReservationEntity::class], version = 2, exportSchema = true)
@TypeConverters(ReservationTypeConverters::class)
abstract class ReservationDatabase : RoomDatabase() {
    abstract fun reservationDao(): ReservationDao

    companion object {
        fun create(context: Context): ReservationDatabase {
            lateinit var database: ReservationDatabase

            database = Room.databaseBuilder(
                context.applicationContext,
                ReservationDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2)
                .withBuildSpecificDatabaseConfiguration { database }
                .build()

            return database
        }

        private const val DATABASE_NAME = "tukkateatteri.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reservations ADD COLUMN admission_type TEXT NOT NULL DEFAULT 'RESERVATION'"
                )
            }
        }

    }
}
