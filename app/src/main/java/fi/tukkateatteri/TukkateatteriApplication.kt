package fi.tukkateatteri

import android.app.Application
import androidx.core.content.edit
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
                googleSheetsClient = GoogleSheetsClient(
                    deviceId = "$DEVICE_LABEL_PREFIX${installationId().take(INSTALLATION_ID_LABEL_LENGTH)}"
                )
            )
        }
    }

    private fun installationId(): String {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        return preferences.getString(PREFERENCE_INSTALLATION_ID, null)
            ?: UUID.randomUUID().toString().also { id ->
                preferences.edit { putString(PREFERENCE_INSTALLATION_ID, id) }
            }
    }

    private companion object {
        private const val PREFERENCES_NAME = "tukkateatteri"
        private const val PREFERENCE_INSTALLATION_ID = "installation_id"
        private const val DEVICE_LABEL_PREFIX = "Android-"
        private const val INSTALLATION_ID_LABEL_LENGTH = 8
    }
}
