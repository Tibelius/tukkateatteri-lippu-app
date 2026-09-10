package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import fi.tukkateatteri.ui.theme.TukkateatteriTheme
import fi.tukkateatteri.logging.AppLog

class MainActivity : ComponentActivity() {
    private val reservationViewModel: ReservationViewModel by viewModels {
        ReservationViewModel.factory(
            (application as TukkateatteriApplication).reservationRepository
        )
    }
    private var pendingGoogleAuthorization: PendingGoogleAuthorization? = null
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            AppLog.debug(LOG_COMPONENT) { "Received Google authorization activity result" }
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            authorizationResult.accessToken?.let { accessToken ->
                AppLog.info(LOG_COMPONENT) { "Google authorization completed successfully" }
                pendingGoogleAuthorization?.onAuthorized?.invoke(accessToken)
            } ?: run {
                AppLog.warning(LOG_COMPONENT) { "Google authorization returned without an access token" }
                pendingGoogleAuthorization?.onUnavailable?.invoke()
            }
        } catch (exception: ApiException) {
            AppLog.warning(LOG_COMPONENT, exception) {
                "Google authorization result could not be read; statusCode=${exception.statusCode}"
            }
            pendingGoogleAuthorization?.onUnavailable?.invoke()
        } finally {
            clearPendingAuthorization()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info(LOG_COMPONENT) { "Application activity created; restoringState=${savedInstanceState != null}" }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TukkateatteriTheme {
                ReservationApp(
                    viewModel = reservationViewModel,
                    onGoogleSheetsTransfer = { spreadsheetUrl ->
                        authorizeGoogleSheets { accessToken ->
                            reservationViewModel.prepareGoogleSheetImport(
                                spreadsheetUrl,
                                accessToken
                            )
                        }
                    },
                    onGoogleSheetsSync = { performance, spreadsheetUrl, showFeedback ->
                        authorizeGoogleSheets { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformance(
                                performance.id,
                                spreadsheetUrl,
                                accessToken,
                                showFeedback
                            )
                        }
                    },
                    onGoogleSheetsSyncAll = { performances, spreadsheetUrl ->
                        authorizeGoogleSheets { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformances(
                                performances,
                                spreadsheetUrl,
                                accessToken
                            )
                        }
                    },
                    onGoogleSheetsMutation = { mutation ->
                        authorizeGoogleSheets(
                            onAuthorized = mutation,
                            onUnavailable = { mutation(null) }
                        )
                    },
                    onChangeGoogleAccount = ::revokeGoogleSheetsAccess
                )
            }
        }
    }

    private fun authorizeGoogleSheets(
        onUnavailable: (() -> Unit)? = null,
        onAuthorized: (String) -> Unit
    ) {
        if (pendingGoogleAuthorization != null) {
            AppLog.warning(LOG_COMPONENT) { "Replacing an unfinished Google authorization request" }
        }
        AppLog.debug(LOG_COMPONENT) { "Starting Google Sheets authorization" }
        pendingGoogleAuthorization = PendingGoogleAuthorization(onAuthorized, onUnavailable)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    AppLog.debug(LOG_COMPONENT) { "Google authorization requires user interaction" }
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        invokeAuthorizationFallback()
                    } else {
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    result.accessToken?.let { accessToken ->
                        AppLog.info(LOG_COMPONENT) { "Google authorization reused an existing grant" }
                        pendingGoogleAuthorization?.onAuthorized?.invoke(accessToken)
                    } ?: run {
                        AppLog.warning(LOG_COMPONENT) { "Google authorization grant had no access token" }
                        pendingGoogleAuthorization?.onUnavailable?.invoke()
                    }
                    clearPendingAuthorization()
                }
            }
            .addOnFailureListener { exception ->
                AppLog.warning(LOG_COMPONENT, exception) { "Google authorization request failed" }
                invokeAuthorizationFallback()
            }
    }

    private fun invokeAuthorizationFallback() {
        AppLog.debug(LOG_COMPONENT) { "Continuing without Google authorization" }
        pendingGoogleAuthorization?.onUnavailable?.invoke()
        clearPendingAuthorization()
    }

    private fun clearPendingAuthorization() {
        pendingGoogleAuthorization = null
    }

    private fun revokeGoogleSheetsAccess() {
        AppLog.info(LOG_COMPONENT) { "Disconnecting the current Google account" }
        clearPendingAuthorization()
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(GOOGLE_SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    AppLog.info(LOG_COMPONENT) { "Google account access disconnected" }
                } else {
                    AppLog.warning(LOG_COMPONENT, task.exception) { "Google account disconnection failed" }
                }
                Toast.makeText(
                    this,
                    R.string.google_account_disconnected,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private companion object {
        const val LOG_COMPONENT = "Auth"
        const val GOOGLE_SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}

private data class PendingGoogleAuthorization(
    val onAuthorized: (String) -> Unit,
    val onUnavailable: (() -> Unit)?
)
