package fi.tukkateatteri.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import fi.tukkateatteri.data.PaymentMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(entities = [ReservationEntity::class], version = 2, exportSchema = true)
@TypeConverters(ReservationTypeConverters::class)
abstract class ReservationDatabase : RoomDatabase() {
    abstract fun reservationDao(): ReservationDao

    companion object {
        fun create(context: Context): ReservationDatabase {
            lateinit var database: ReservationDatabase
            val seedDataCallback = object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    databaseScope.launch {
                        database.reservationDao().insertAll(initialReservations)
                    }
                }
            }

            database = Room.databaseBuilder(
                context.applicationContext,
                ReservationDatabase::class.java,
                DATABASE_NAME
            ).addMigrations(MIGRATION_1_2)
                .addCallback(seedDataCallback)
                .build()

            return database
        }

        private const val DATABASE_NAME = "tukkateatteri.db"

        private val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reservations ADD COLUMN admission_type TEXT NOT NULL DEFAULT 'RESERVATION'"
                )
            }
        }

        private val initialReservations = listOf(
            ReservationEntity(
                lastName = "Laine",
                firstName = "Aino",
                contact = "040 123 4567",
                seatCount = 2
            ),
            ReservationEntity(
                lastName = "Mäkinen",
                firstName = "Pekka",
                contact = "pekka.makinen@example.fi",
                seatCount = 4,
                isPresent = true,
                paymentMethod = PaymentMethod.CARD
            ),
            ReservationEntity(
                lastName = "Nieminen",
                firstName = "Sari",
                contact = "050 765 4321",
                seatCount = 1,
                isPresent = true
            )
        )
    }
}
