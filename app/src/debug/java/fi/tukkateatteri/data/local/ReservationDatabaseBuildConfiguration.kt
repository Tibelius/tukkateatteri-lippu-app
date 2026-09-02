package fi.tukkateatteri.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import fi.tukkateatteri.data.PaymentMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal fun RoomDatabase.Builder<ReservationDatabase>.withBuildSpecificDatabaseConfiguration(
    databaseProvider: () -> ReservationDatabase
): RoomDatabase.Builder<ReservationDatabase> = addCallback(
    object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            databaseScope.launch {
                databaseProvider().reservationDao().insertAll(initialReservations)
            }
        }
    }
)

private val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
