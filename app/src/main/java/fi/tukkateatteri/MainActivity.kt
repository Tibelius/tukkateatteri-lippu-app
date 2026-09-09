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
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            authorizationResult.accessToken?.let { accessToken ->
                pendingGoogleAuthorization?.onAuthorized?.invoke(accessToken)
            } ?: pendingGoogleAuthorization?.onUnavailable?.invoke()
        } catch (_: ApiException) {
            pendingGoogleAuthorization?.onUnavailable?.invoke()
        } finally {
            clearPendingAuthorization()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    onGoogleSheetsSync = { performance, spreadsheetUrl, showError ->
                        authorizeGoogleSheets { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformance(
                                performance.id,
                                spreadsheetUrl,
                                accessToken,
                                showError
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
        pendingGoogleAuthorization = PendingGoogleAuthorization(onAuthorized, onUnavailable)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GOOGLE_SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
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
                        pendingGoogleAuthorization?.onAuthorized?.invoke(accessToken)
                    } ?: pendingGoogleAuthorization?.onUnavailable?.invoke()
                    clearPendingAuthorization()
                }
            }
            .addOnFailureListener { invokeAuthorizationFallback() }
    }

    private fun invokeAuthorizationFallback() {
        pendingGoogleAuthorization?.onUnavailable?.invoke()
        clearPendingAuthorization()
    }

    private fun clearPendingAuthorization() {
        pendingGoogleAuthorization = null
    }

    private fun revokeGoogleSheetsAccess() {
        clearPendingAuthorization()
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(GOOGLE_SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).revokeAccess(request)
            .addOnCompleteListener {
                Toast.makeText(
                    this,
                    R.string.google_account_disconnected,
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private companion object {
        const val GOOGLE_SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}

private data class PendingGoogleAuthorization(
    val onAuthorized: (String) -> Unit,
    val onUnavailable: (() -> Unit)?
)
