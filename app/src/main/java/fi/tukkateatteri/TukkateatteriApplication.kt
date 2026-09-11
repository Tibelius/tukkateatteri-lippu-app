package fi.tukkateatteri

import android.app.Application
import androidx.core.content.edit
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.RoomReservationRepository
import fi.tukkateatteri.data.local.ReservationDatabase
import fi.tukkateatteri.data.spreadsheet.GoogleSheetsClient
import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.payment.configureCardPaymentSdk
import java.util.UUID

class TukkateatteriApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureCardPaymentSdk(this)
    }

    val reservationRepository: ReservationRepository by lazy {
        AppLog.info(LOG_COMPONENT) { "Initializing reservation repository and Room database" }
        ReservationDatabase.create(this).let { database ->
            RoomReservationRepository(
                database = database,
                reservationDao = database.reservationDao(),
                performanceDao = database.performanceDao(),
                googleSheetSourceDao = database.googleSheetSourceDao(),
                pendingSheetChangeDao = database.pendingSheetChangeDao(),
                sheetFieldDefinitionDao = database.sheetFieldDefinitionDao(),
                sheetFieldAliasDao = database.sheetFieldAliasDao(),
                googleSheetsClient = GoogleSheetsClient(
                    deviceId = "$DEVICE_LABEL_PREFIX${installationId().take(INSTALLATION_ID_LABEL_LENGTH)}"
                )
            )
        }
    }

    private fun installationId(): String {
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        return preferences.getString(PREFERENCE_INSTALLATION_ID, null)
            ?.also { AppLog.debug(LOG_COMPONENT) { "Reusing installation identifier ${it.take(INSTALLATION_ID_LABEL_LENGTH)}" } }
            ?: UUID.randomUUID().toString().also { id ->
                AppLog.info(LOG_COMPONENT) { "Created installation identifier ${id.take(INSTALLATION_ID_LABEL_LENGTH)}" }
                preferences.edit { putString(PREFERENCE_INSTALLATION_ID, id) }
            }
    }

    private companion object {
        private const val LOG_COMPONENT = "Application"
        private const val PREFERENCES_NAME = "tukkateatteri"
        private const val PREFERENCE_INSTALLATION_ID = "installation_id"
        private const val DEVICE_LABEL_PREFIX = "Android-"
        private const val INSTALLATION_ID_LABEL_LENGTH = 8
    }
}
