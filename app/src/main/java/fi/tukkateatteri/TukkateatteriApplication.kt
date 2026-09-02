package fi.tukkateatteri

import android.app.Application
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.RoomReservationRepository
import fi.tukkateatteri.data.local.ReservationDatabase

class TukkateatteriApplication : Application() {
    val reservationRepository: ReservationRepository by lazy {
        RoomReservationRepository(ReservationDatabase.create(this).reservationDao())
    }
}
