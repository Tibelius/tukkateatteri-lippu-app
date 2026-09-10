package fi.tukkateatteri.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fi.tukkateatteri.logging.AppLog

@Database(
    entities = [
        ReservationEntity::class,
        TicketSaleEntity::class,
        PaymentAllocationEntity::class,
        ReservedTicketAllocationEntity::class,
        GoogleSheetSourceEntity::class,
        PerformanceEntity::class,
        PendingSheetChangeEntity::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(ReservationTypeConverters::class)
abstract class ReservationDatabase : RoomDatabase() {
    abstract fun reservationDao(): ReservationDao
    abstract fun performanceDao(): PerformanceDao
    abstract fun googleSheetSourceDao(): GoogleSheetSourceDao
    abstract fun pendingSheetChangeDao(): PendingSheetChangeDao

    companion object {
        fun create(context: Context): ReservationDatabase {
            AppLog.debug(LOG_COMPONENT) { "Opening Room database '$DATABASE_NAME' at schema version 12" }
            return Room
                .databaseBuilder(
                    context.applicationContext,
                    ReservationDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(*ReservationDatabaseMigrations.ALL)
                .build()
        }

        private const val DATABASE_NAME = "tukkateatteri.db"
        private const val LOG_COMPONENT = "Room"

    }
}
