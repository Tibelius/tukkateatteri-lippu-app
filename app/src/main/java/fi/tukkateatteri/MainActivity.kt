package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.ui.dialogs.AddAdmissionDialog
import fi.tukkateatteri.ui.dialogs.AddAdmissionTypeDialog
import fi.tukkateatteri.ui.dialogs.DeleteAllReservationsDialog
import fi.tukkateatteri.ui.dialogs.DeleteReservationDialog
import fi.tukkateatteri.ui.dialogs.GoogleSheetSourceManagerDialog
import fi.tukkateatteri.ui.dialogs.ReservationDialog
import fi.tukkateatteri.ui.screens.ReservationListScreen
import fi.tukkateatteri.ui.theme.TukkateatteriTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
                    },
                    onGoogleSheetsSync = { performance, spreadsheetUrl ->
                        authorizeGoogleSheets { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformance(
                                performance.id,
                                spreadsheetUrl,
                                accessToken
                            )
                        }
                    },
                    onGoogleSheetsMutation = ::authorizeGoogleSheets,
                    onChangeGoogleAccount = ::revokeGoogleSheetsAccess
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

    private fun revokeGoogleSheetsAccess() {
        pendingGoogleAuthorization = null
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(SHEETS_SCOPE)))
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
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}

@Composable
private fun ReservationApp(
    viewModel: ReservationViewModel,
    onGoogleSheetsTransfer: (SpreadsheetAction, String, String) -> Unit,
    onGoogleSheetsSync: (Performance, String) -> Unit,
    onGoogleSheetsMutation: ((String) -> Unit) -> Unit,
    onChangeGoogleAccount: () -> Unit
) {
    val reservations by viewModel.reservations.collectAsStateWithLifecycle()
    val activePerformance by viewModel.activePerformance.collectAsStateWithLifecycle()
    val performances by viewModel.performances.collectAsStateWithLifecycle()
    val transferMessage by viewModel.transferMessage.collectAsStateWithLifecycle()
    val isTransferInProgress by viewModel.isTransferInProgress.collectAsStateWithLifecycle()
    val googleSheetSources by viewModel.googleSheetSources.collectAsStateWithLifecycle()
    var selectedReservationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reservationToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAdmissionTypeDialog by rememberSaveable { mutableStateOf(false) }
    var selectedAdmissionTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllReservationsConfirmation by rememberSaveable { mutableStateOf(false) }
    var showGoogleSheetSourceManager by rememberSaveable { mutableStateOf(false) }
    var showPerformanceEditor by rememberSaveable { mutableStateOf(false) }
    var performanceToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var spreadsheetAction by rememberSaveable { mutableStateOf<SpreadsheetAction?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            PerformanceDrawerContent(
                performances = performances,
                googleSheetSources = googleSheetSources,
                onSelectPerformance = { performance ->
                    viewModel.selectPerformance(performance.id)
                    coroutineScope.launch { drawerState.close() }
                },
                onAddPerformance = {
                    showPerformanceEditor = true
                    coroutineScope.launch { drawerState.close() }
                },
                onSyncPerformance = { performance, source ->
                    onGoogleSheetsSync(performance, source.spreadsheetUrl)
                    coroutineScope.launch { drawerState.close() }
                },
                onDeletePerformance = { performance ->
                    performanceToDeleteId = performance.id
                    coroutineScope.launch { drawerState.close() }
                }
            )
        }
    ) {
        ReservationListScreen(
            activePerformance = activePerformance,
            reservations = reservations,
            onOpenPerformanceMenu = { coroutineScope.launch { drawerState.open() } },
            onReservationClick = { reservation -> selectedReservationId = reservation.id },
            onAddClick = {
                if (activePerformance == null) showPerformanceEditor = true else showAdmissionTypeDialog = true
            },
            onImportClick = { spreadsheetAction = SpreadsheetAction.IMPORT },
            onExportClick = { spreadsheetAction = SpreadsheetAction.EXPORT },
            onManageGoogleSheetSourcesClick = { showGoogleSheetSourceManager = true },
            onChangeGoogleAccountClick = onChangeGoogleAccount,
            onDeleteAllClick = { showDeleteAllReservationsConfirmation = true }
        )
    }

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

    if (showPerformanceEditor) {
        PerformanceEditorDialog(
            onDismiss = { showPerformanceEditor = false },
            onSave = { actName, date ->
                viewModel.createPerformance(actName, date)
                showPerformanceEditor = false
            }
        )
    }

    performances.find { it.id == performanceToDeleteId }?.let { performance ->
        DeletePerformanceDialog(
            performance = performance,
            onDismiss = { performanceToDeleteId = null },
            onConfirm = {
                viewModel.deletePerformance(performance.id)
                performanceToDeleteId = null
            }
        )
    }

    selectedAdmissionTypeName?.let { typeName ->
        val admissionType = AdmissionType.valueOf(typeName)
        AddAdmissionDialog(
            admissionType = admissionType,
            onDismiss = { selectedAdmissionTypeName = null },
            onSave = { lastName, firstName, contact, seatCount, reservedTicketAllocations ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.addAdmission(
                        lastName = lastName,
                        firstName = firstName,
                        contact = contact,
                        seatCount = seatCount,
                        admissionType = admissionType,
                        reservedTicketAllocations = reservedTicketAllocations,
                        accessToken = accessToken
                    )
                }
                selectedAdmissionTypeName = null
            }
        )
    }

    reservations.find { it.id == selectedReservationId }?.let { reservation ->
        ReservationDialog(
            reservation = reservation,
            onDismiss = { selectedReservationId = null },
            onSave = { updatedReservation ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.updateReservation(updatedReservation, accessToken)
                }
                selectedReservationId = null
            },
            onUpdateArrivalCount = { reservationId, arrivalCount ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.updateArrivalCount(reservationId, arrivalCount, accessToken)
                }
            },
            onAddTicketSale = { ticketType, quantity, payments ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.addTicketSale(reservation.id, ticketType, quantity, payments, accessToken)
                }
            },
            onUpdateTicketSale = { ticketSaleId, ticketType, quantity, payments ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.updateTicketSale(ticketSaleId, ticketType, quantity, payments, accessToken)
                }
            },
            onDeleteTicketSale = { ticketSaleId ->
                onGoogleSheetsMutation { accessToken ->
                    viewModel.deleteTicketSale(ticketSaleId, accessToken)
                }
            },
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
                onGoogleSheetsMutation { accessToken ->
                    viewModel.deleteReservation(reservation.id, accessToken)
                }
                reservationToDeleteId = null
            }
        )
    }

    if (showDeleteAllReservationsConfirmation) {
        DeleteAllReservationsDialog(
            onDismiss = { showDeleteAllReservationsConfirmation = false },
            onConfirm = {
                onGoogleSheetsMutation { accessToken ->
                    viewModel.deleteAllReservations(accessToken)
                }
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

@Composable
private fun PerformanceDrawerContent(
    performances: List<Performance>,
    googleSheetSources: List<GoogleSheetSource>,
    onSelectPerformance: (Performance) -> Unit,
    onAddPerformance: () -> Unit,
    onSyncPerformance: (Performance, GoogleSheetSource) -> Unit,
    onDeletePerformance: (Performance) -> Unit
) {
    var expandedActName by rememberSaveable { mutableStateOf<String?>(null) }
    var hasExplicitExpansionSelection by rememberSaveable { mutableStateOf(false) }
    val performancesByAct = performances
        .groupBy(Performance::actName)
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val sourcesByAct = googleSheetSources.associateBy(GoogleSheetSource::actName)

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.performances),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedButton(
                onClick = onAddPerformance,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.add_performance))
            }
            HorizontalDivider()
            performancesByAct.forEach { (actName, actPerformances) ->
                val isExpanded = if (hasExplicitExpansionSelection) {
                    expandedActName == actName
                } else {
                    actPerformances.any(Performance::isActive)
                }
                ListItem(
                    headlineContent = { Text(actName) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.performance_date_count, actPerformances.size, actPerformances.size))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            hasExplicitExpansionSelection = true
                            expandedActName = if (isExpanded) null else actName
                        },
                    trailingContent = {
                        Icon(
                            imageVector = if (isExpanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = null
                        )
                    }
                )
                if (isExpanded) {
                    actPerformances
                        .sortedWith(
                            compareBy<Performance> {
                                it.date.toPerformanceDateOrNull() ?: LocalDate.MAX
                            }
                                .thenBy(Performance::date)
                        )
                        .forEach { performance ->
                            PerformanceDrawerDateItem(
                                performance = performance,
                                source = sourcesByAct[actName],
                                onSelect = { onSelectPerformance(performance) },
                                onSync = { source -> onSyncPerformance(performance, source) },
                                onDelete = { onDeletePerformance(performance) }
                            )
                        }
                }
            }
        }
    }
}

private fun String.toPerformanceDateOrNull(): LocalDate? = runCatching {
    LocalDate.parse(trim(), DateTimeFormatter.ofPattern("d.M.uuuu"))
}.getOrNull()

@Composable
private fun PerformanceDrawerDateItem(
    performance: Performance,
    source: GoogleSheetSource?,
    onSelect: () -> Unit,
    onSync: (GoogleSheetSource) -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(performance.date) },
        supportingContent = {
            if (performance.canSyncFromGoogleSheets && source == null) {
                Text(stringResource(R.string.google_sheets_sync_source_missing))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .clickable(onClick = onSelect),
        trailingContent = {
            Row {
                if (performance.canSyncFromGoogleSheets && source != null) {
                    IconButton(onClick = { onSync(source) }) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.sync_google_sheet)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_performance_action),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = if (performance.isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    )
}

@Composable
private fun DeletePerformanceDialog(
    performance: Performance,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_performance_title)) },
        text = { Text(stringResource(R.string.delete_performance_message, performance.displayName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PerformanceEditorDialog(
    onDismiss: () -> Unit,
    onSave: (actName: String, date: String) -> Unit
) {
    var actName by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_performance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = actName,
                    onValueChange = { actName = it },
                    label = { Text(stringResource(R.string.performance_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text(stringResource(R.string.performance_date)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(actName, date) },
                enabled = actName.isNotBlank() && date.isNotBlank()
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
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
