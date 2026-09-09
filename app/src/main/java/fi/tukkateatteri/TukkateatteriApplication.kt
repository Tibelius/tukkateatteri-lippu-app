package fi.tukkateatteri

import android.app.Application
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.RoomReservationRepository
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import java.util.UUID

class TukkateatteriApplication : Application() {
    val reservationRepository: ReservationRepository by lazy {
        ReservationDatabase.create(this).let { database ->
            RoomReservationRepository(
                database = database,
                reservationDao = database.reservationDao(),
                performanceDao = database.performanceDao(),
                googleSheetSourceDao = database.googleSheetSourceDao(),
                pendingSheetChangeDao = database.pendingSheetChangeDao(),
                googleSheetsClient = GoogleSheetsClient("Android ${installationId().take(8)}")
            )
        }
    }

    private fun installationId(): String {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        return preferences.getString(PREFERENCE_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also { id ->
                preferences.edit().putString(PREFERENCE_INSTALLATION_ID, id).apply()
            }
    }

    private companion object {
        const val PREFERENCES_NAME = "tukkateatteri"
        const val PREFERENCE_INSTALLATION_ID = "installation_id"
    }
}
