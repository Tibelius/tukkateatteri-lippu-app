package fi.tukkateatteri.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import fi.tukkateatteri.logging.AppLog

private const val DATABASE_VERSION = 14

@Database(
    entities = [
        ReservationEntity::class,
        TicketSaleEntity::class,
        PaymentAllocationEntity::class,
        ReservedTicketAllocationEntity::class,
        GoogleSheetSourceEntity::class,
        PerformanceEntity::class,
        PendingSheetChangeEntity::class,
        SheetFieldDefinitionEntity::class,
        SheetFieldAliasEntity::class
    ],
    version = DATABASE_VERSION,
    exportSchema = true
)
@TypeConverters(ReservationTypeConverters::class)
abstract class ReservationDatabase : RoomDatabase() {
    abstract fun reservationDao(): ReservationDao
    abstract fun performanceDao(): PerformanceDao
    abstract fun googleSheetSourceDao(): GoogleSheetSourceDao
    abstract fun pendingSheetChangeDao(): PendingSheetChangeDao
    abstract fun sheetFieldDefinitionDao(): SheetFieldDefinitionDao
    abstract fun sheetFieldAliasDao(): SheetFieldAliasDao

    companion object {
        fun create(context: Context): ReservationDatabase {
            AppLog.debug(LOG_COMPONENT) {
                "Opening Room database '$DATABASE_NAME' at schema version $DATABASE_VERSION"
            }
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
