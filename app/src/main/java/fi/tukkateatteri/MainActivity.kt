package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.spreadsheet.GoogleSheetImportCandidate
import fi.tukkateatteri.ui.dialogs.AddAdmissionDialog
import fi.tukkateatteri.ui.dialogs.AddAdmissionTypeDialog
import fi.tukkateatteri.ui.dialogs.DeleteAllReservationsDialog
import fi.tukkateatteri.ui.dialogs.DeleteReservationDialog
import fi.tukkateatteri.ui.dialogs.GoogleSheetSourceManagerDialog
import fi.tukkateatteri.ui.dialogs.ReservationDialog
import fi.tukkateatteri.ui.screens.ReservationListScreen
import fi.tukkateatteri.ui.theme.TukkateatteriTheme

class MainActivity : ComponentActivity() {
    private val reservationViewModel: ReservationViewModel by viewModels {
        ReservationViewModel.factory(
            (application as TukkateatteriApplication).reservationRepository
        )
    }
    private var pendingGoogleAuthorization: ((String) -> Unit)? = null
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            authorizationResult.accessToken?.let { pendingGoogleAuthorization?.invoke(it) }
        } catch (_: ApiException) {
            // The user canceled Google authorization.
        } finally {
            pendingGoogleAuthorization = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TukkateatteriTheme {
                ReservationApp(
                    viewModel = reservationViewModel,
                    onGoogleSheetsTransfer = { action, spreadsheetUrl, sheetTitle ->
                        authorizeGoogleSheets { accessToken ->
                            when (action) {
                                SpreadsheetAction.IMPORT -> {
                                    reservationViewModel.prepareGoogleSheetImport(
                                        spreadsheetUrl,
                                        accessToken
                                    )
                                }
                                SpreadsheetAction.EXPORT -> {
                                    reservationViewModel.exportGoogleSheet(
                                        spreadsheetUrl,
                                        sheetTitle,
                                        accessToken
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private fun authorizeGoogleSheets(onAuthorized: (String) -> Unit) {
        pendingGoogleAuthorization = onAuthorized
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let { pendingIntent ->
                        googleAuthorizationLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } ?: run {
                        pendingGoogleAuthorization = null
                    }
                } else {
                    result.accessToken?.let { pendingGoogleAuthorization?.invoke(it) }
                    pendingGoogleAuthorization = null
                }
            }
            .addOnFailureListener { pendingGoogleAuthorization = null }
    }

    private companion object {
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}

@Composable
private fun ReservationApp(
    viewModel: ReservationViewModel,
    onGoogleSheetsTransfer: (SpreadsheetAction, String, String) -> Unit
) {
    val reservations by viewModel.reservations.collectAsStateWithLifecycle()
    val transferMessage by viewModel.transferMessage.collectAsStateWithLifecycle()
    val importCandidates by viewModel.importCandidates.collectAsStateWithLifecycle()
    val isTransferInProgress by viewModel.isTransferInProgress.collectAsStateWithLifecycle()
    val googleSheetSources by viewModel.googleSheetSources.collectAsStateWithLifecycle()
    var selectedReservationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reservationToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAdmissionTypeDialog by rememberSaveable { mutableStateOf(false) }
    var selectedAdmissionTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllReservationsConfirmation by rememberSaveable { mutableStateOf(false) }
    var showGoogleSheetSourceManager by rememberSaveable { mutableStateOf(false) }
    var spreadsheetAction by rememberSaveable { mutableStateOf<SpreadsheetAction?>(null) }

    ReservationListScreen(
        reservations = reservations,
        onReservationClick = { reservation -> selectedReservationId = reservation.id },
        onAddClick = { showAdmissionTypeDialog = true },
        onImportClick = { spreadsheetAction = SpreadsheetAction.IMPORT },
        onExportClick = { spreadsheetAction = SpreadsheetAction.EXPORT },
        onManageGoogleSheetSourcesClick = { showGoogleSheetSourceManager = true },
        onDeleteAllClick = { showDeleteAllReservationsConfirmation = true }
    )

    LaunchedEffect(viewModel) {
        viewModel.addedReservationIds.collect { reservationId ->
            selectedReservationId = reservationId
        }
    }

    if (showAdmissionTypeDialog) {
        AddAdmissionTypeDialog(
            onDismiss = { showAdmissionTypeDialog = false },
            onTypeSelected = { admissionType ->
                selectedAdmissionTypeName = admissionType.name
                showAdmissionTypeDialog = false
            }
        )
    }

    selectedAdmissionTypeName?.let { typeName ->
        val admissionType = AdmissionType.valueOf(typeName)
        AddAdmissionDialog(
            admissionType = admissionType,
            onDismiss = { selectedAdmissionTypeName = null },
            onSave = { lastName, firstName, contact, seatCount, reservedTicketAllocations ->
                viewModel.addAdmission(
                    lastName = lastName,
                    firstName = firstName,
                    contact = contact,
                    seatCount = seatCount,
                    admissionType = admissionType,
                    reservedTicketAllocations = reservedTicketAllocations
                )
                selectedAdmissionTypeName = null
            }
        )
    }

    reservations.find { it.id == selectedReservationId }?.let { reservation ->
        ReservationDialog(
            reservation = reservation,
            onDismiss = { selectedReservationId = null },
            onSave = { updatedReservation ->
                viewModel.updateReservation(updatedReservation)
                selectedReservationId = null
            },
            onUpdateArrivalCount = viewModel::updateArrivalCount,
            onAddTicketSale = { ticketType, quantity, payments ->
                viewModel.addTicketSale(reservation.id, ticketType, quantity, payments)
            },
            onDeleteTicketSale = viewModel::deleteTicketSale,
            onDelete = {
                selectedReservationId = null
                reservationToDeleteId = reservation.id
            }
        )
    }

    reservations.find { it.id == reservationToDeleteId }?.let { reservation ->
        DeleteReservationDialog(
            reservation = reservation,
            onDismiss = { reservationToDeleteId = null },
            onConfirm = {
                viewModel.deleteReservation(reservation.id)
                reservationToDeleteId = null
            }
        )
    }

    if (showDeleteAllReservationsConfirmation) {
        DeleteAllReservationsDialog(
            onDismiss = { showDeleteAllReservationsConfirmation = false },
            onConfirm = {
                viewModel.deleteAllReservations()
                showDeleteAllReservationsConfirmation = false
            }
        )
    }

    if (showGoogleSheetSourceManager) {
        GoogleSheetSourceManagerDialog(
            sources = googleSheetSources,
            onDismiss = { showGoogleSheetSourceManager = false },
            onSave = viewModel::saveGoogleSheetSource,
            onDelete = viewModel::deleteGoogleSheetSource
        )
    }

    transferMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTransferMessage,
            title = { Text(stringResource(R.string.google_sheets_transfer)) },
            text = { Text(stringResource(message.messageResId, *message.formatArgs.toTypedArray())) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTransferMessage) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    if (isTransferInProgress) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.google_sheets_transfer)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.google_sheets_loading))
                }
            },
            confirmButton = {}
        )
    }

    if (importCandidates.isNotEmpty() && !isTransferInProgress) {
        ImportPerformanceDialog(
            candidates = importCandidates,
            onDismiss = viewModel::dismissImportCandidates,
            onSelect = viewModel::importGoogleSheet
        )
    }

    spreadsheetAction?.let { action ->
        SpreadsheetTransferDialog(
            action = action,
            sources = googleSheetSources,
            onDismiss = { spreadsheetAction = null },
            onTransfer = { spreadsheetUrl, sheetTitle ->
                onGoogleSheetsTransfer(action, spreadsheetUrl, sheetTitle)
                spreadsheetAction = null
            }
        )
    }
}

@Composable
private fun ImportPerformanceDialog(
    candidates: List<GoogleSheetImportCandidate>,
    onDismiss: () -> Unit,
    onSelect: (GoogleSheetImportCandidate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_performance_date)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(candidates.first().performanceName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                candidates.forEach { candidate ->
                    TextButton(onClick = { onSelect(candidate) }, modifier = Modifier.fillMaxWidth()) {
                        Text(candidate.date, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun SpreadsheetTransferDialog(
    action: SpreadsheetAction,
    sources: List<GoogleSheetSource>,
    onDismiss: () -> Unit,
    onTransfer: (String, String) -> Unit
) {
    var spreadsheetUrl by rememberSaveable(action) { mutableStateOf("") }
    var sheetTitle by rememberSaveable(action) { mutableStateOf("") }
    var isSourceMenuExpanded by rememberSaveable(action) { mutableStateOf(false) }
    var selectedSourceName by rememberSaveable(action) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.titleResId)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(action.messageResId))
                if (sources.isNotEmpty()) {
                    Box {
                        OutlinedButton(
                            onClick = { isSourceMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedSourceName ?: stringResource(R.string.select_saved_google_sheet))
                        }
                        DropdownMenu(
                            expanded = isSourceMenuExpanded,
                            onDismissRequest = { isSourceMenuExpanded = false }
                        ) {
                            sources.forEach { source ->
                                DropdownMenuItem(
                                    text = { Text(source.actName) },
                                    onClick = {
                                        spreadsheetUrl = source.spreadsheetUrl
                                        selectedSourceName = source.actName
                                        isSourceMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = spreadsheetUrl,
                    onValueChange = {
                        spreadsheetUrl = it
                        selectedSourceName = null
                    },
                    label = { Text(stringResource(R.string.google_sheet_url)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (action == SpreadsheetAction.EXPORT) {
                    OutlinedTextField(
                        value = sheetTitle,
                        onValueChange = { sheetTitle = it },
                        label = { Text(stringResource(R.string.google_sheet_tab)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTransfer(spreadsheetUrl, sheetTitle) }, enabled = spreadsheetUrl.isNotBlank() && (action == SpreadsheetAction.IMPORT || sheetTitle.isNotBlank())) { Text(stringResource(action.titleResId)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private enum class SpreadsheetAction(
    @param:StringRes val titleResId: Int,
    @param:StringRes val messageResId: Int
) {
    IMPORT(
        R.string.import_spreadsheet,
        R.string.import_spreadsheet_message
    ),
    EXPORT(
        R.string.export_spreadsheet,
        R.string.export_spreadsheet_message
    )
}
