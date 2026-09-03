package fi.tukkateatteri.data.local

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
                val database = databaseProvider()
                val performanceId = database.performanceDao().insert(initialPerformance)
                database.reservationDao().insertAll(
                    initialReservations.map { reservation ->
                        reservation.copy(performanceId = performanceId)
                    }
                )
            }
        }
    }
)

private val databaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val initialReservations = listOf(
    ReservationEntity(
        performanceId = 0,
        lastName = "Laine",
        firstName = "Aino",
        contact = "040 123 4567",
        seatCount = 2
    ),
    ReservationEntity(
        performanceId = 0,
        lastName = "Mäkinen",
        firstName = "Pekka",
        contact = "pekka.makinen@example.fi",
        seatCount = 4
    ),
    ReservationEntity(
        performanceId = 0,
        lastName = "Nieminen",
        firstName = "Sari",
        contact = "050 765 4321",
        seatCount = 1
    )
)

private val initialPerformance = PerformanceEntity(
    actName = "Esimerkkiesitys",
    date = "1.1.2027",
    isActive = true
)
