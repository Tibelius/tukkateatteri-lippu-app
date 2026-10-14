package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
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
    private val googleAuthorizationRequests = GoogleAuthorizationRequests()
    private var authorizationResolutionId: Long? = null
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val attemptId = authorizationResolutionId ?: return@registerForActivityResult
        authorizationResolutionId = null
        try {
            AppLog.debug(LOG_COMPONENT) { "Received Google authorization activity result" }
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            authorizationResult.accessToken?.let { accessToken ->
                AppLog.info(LOG_COMPONENT) { "Google authorization completed successfully" }
                googleAuthorizationRequests.complete(attemptId, accessToken)
            } ?: run {
                AppLog.warning(LOG_COMPONENT) { "Google authorization returned without an access token" }
                googleAuthorizationRequests.complete(attemptId, accessToken = null)
            }
        } catch (exception: ApiException) {
            AppLog.warning(LOG_COMPONENT, exception) {
                "Google authorization result could not be read; statusCode=${exception.statusCode}"
            }
            googleAuthorizationRequests.complete(attemptId, accessToken = null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info(LOG_COMPONENT) { "Application activity created; restoringState=${savedInstanceState != null}" }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TukkateatteriTheme {
                Column {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            onGoogleSheetsSync = {
                                    performance,
                                    spreadsheetUrl,
                                    showFeedback,
                                    retryMissingRows ->
                                authorizeGoogleSheets { accessToken ->
                                    reservationViewModel.syncGoogleSheetPerformance(
                                        performance.id,
                                        spreadsheetUrl,
                                        accessToken,
                                        showFeedback,
                                        retryMissingRows
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
                    if (IS_DEBUG_BUILD) DebugBuildBanner()
                }
            }
        }
    }

    private fun authorizeGoogleSheets(
        onUnavailable: (() -> Unit)? = null,
        onAuthorized: (String) -> Unit
    ) {
        val attemptId = googleAuthorizationRequests.enqueue(onAuthorized, onUnavailable) ?: return
        AppLog.debug(LOG_COMPONENT) { "Starting Google Sheets authorization" }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (!googleAuthorizationRequests.isCurrent(attemptId)) return@addOnSuccessListener
                if (result.hasResolution()) {
                    AppLog.debug(LOG_COMPONENT) { "Google authorization requires user interaction" }
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        invokeAuthorizationFallback(attemptId)
                    } else {
                        authorizationResolutionId = attemptId
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    }
                } else {
                    result.accessToken?.let { accessToken ->
                        AppLog.info(LOG_COMPONENT) { "Google authorization reused an existing grant" }
                        googleAuthorizationRequests.complete(attemptId, accessToken)
                    } ?: run {
                        AppLog.warning(LOG_COMPONENT) { "Google authorization grant had no access token" }
                        googleAuthorizationRequests.complete(attemptId, accessToken = null)
                    }
                }
            }
            .addOnFailureListener { exception ->
                AppLog.warning(LOG_COMPONENT, exception) { "Google authorization request failed" }
                invokeAuthorizationFallback(attemptId)
            }
    }

    private fun invokeAuthorizationFallback(attemptId: Long) {
        AppLog.debug(LOG_COMPONENT) { "Continuing without Google authorization" }
        googleAuthorizationRequests.complete(attemptId, accessToken = null)
    }

    private fun revokeGoogleSheetsAccess() {
        AppLog.info(LOG_COMPONENT) { "Disconnecting the current Google account" }
        googleAuthorizationRequests.cancel()
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
                    if (task.isSuccessful) R.string.google_account_disconnected
                    else R.string.google_account_disconnect_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private companion object {
        const val LOG_COMPONENT = "Auth"
        const val GOOGLE_SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}
