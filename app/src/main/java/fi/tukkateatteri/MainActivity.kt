package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.remember
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
    private var pendingGoogleAuthorizationFallback: (() -> Unit)? = null
    private val googleAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        try {
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(result.data)
            authorizationResult.accessToken?.let { pendingGoogleAuthorization?.invoke(it) }
                ?: pendingGoogleAuthorizationFallback?.invoke()
        } catch (_: ApiException) {
            pendingGoogleAuthorizationFallback?.invoke()
        } finally {
            pendingGoogleAuthorization = null
            pendingGoogleAuthorizationFallback = null
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
                        authorizeGoogleSheets(onAuthorized = { accessToken ->
                            reservationViewModel.prepareGoogleSheetImport(
                                spreadsheetUrl,
                                accessToken
                            )
                        })
                    },
                    onGoogleSheetsSync = { performance, spreadsheetUrl, showError ->
                        authorizeGoogleSheets(onAuthorized = { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformance(
                                performance.id,
                                spreadsheetUrl,
                                accessToken,
                                showError
                            )
                        })
                    },
                    onGoogleSheetsSyncAll = { performances, spreadsheetUrl ->
                        authorizeGoogleSheets(onAuthorized = { accessToken ->
                            reservationViewModel.syncGoogleSheetPerformances(
                                performances.map(Performance::id),
                                spreadsheetUrl,
                                accessToken
                            )
                        })
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
        onAuthorized: (String) -> Unit,
        onUnavailable: (() -> Unit)? = null
    ) {
        pendingGoogleAuthorization = onAuthorized
        pendingGoogleAuthorizationFallback = onUnavailable
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
                        pendingGoogleAuthorizationFallback?.invoke()
                        pendingGoogleAuthorization = null
                        pendingGoogleAuthorizationFallback = null
                    }
                } else {
                    result.accessToken?.let { pendingGoogleAuthorization?.invoke(it) }
                    pendingGoogleAuthorization = null
                    pendingGoogleAuthorizationFallback = null
                }
            }
            .addOnFailureListener {
                pendingGoogleAuthorizationFallback?.invoke()
                pendingGoogleAuthorization = null
                pendingGoogleAuthorizationFallback = null
            }
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
    onGoogleSheetsTransfer: (String) -> Unit,
    onGoogleSheetsSync: (Performance, String, Boolean) -> Unit,
    onGoogleSheetsSyncAll: (List<Performance>, String) -> Unit,
    onGoogleSheetsMutation: ((String?) -> Unit) -> Unit,
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
    var performancesToImport by remember { mutableStateOf<List<Performance>?>(null) }
    var spreadsheetUrlToImport by remember { mutableStateOf<String?>(null) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
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
                    onGoogleSheetsSync(performance, source.spreadsheetUrl, true)
                    coroutineScope.launch { drawerState.close() }
                },
                onSyncAct = { actPerformances, source ->
                    performancesToImport = actPerformances
                    spreadsheetUrlToImport = source.spreadsheetUrl
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
            onImportClick = { showImportDialog = true },
            onManageGoogleSheetSourcesClick = { showGoogleSheetSourceManager = true },
            onChangeGoogleAccountClick = onChangeGoogleAccount,
            onSyncPendingClick = {
                activePerformance?.let { performance ->
                    googleSheetSources
                        .firstOrNull { source -> source.actName == performance.actName }
                        ?.let { source -> onGoogleSheetsSync(performance, source.spreadsheetUrl, true) }
                }
            },
            onDeleteAllClick = { showDeleteAllReservationsConfirmation = true }
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.addedReservationIds.collect { reservationId ->
            selectedReservationId = reservationId
        }
    }

    LaunchedEffect(activePerformance?.id, googleSheetSources) {
        activePerformance?.let { performance ->
            googleSheetSources
                .firstOrNull { source -> source.actName == performance.actName }
                ?.takeIf { performance.canSyncFromGoogleSheets }
                ?.let { source -> onGoogleSheetsSync(performance, source.spreadsheetUrl, false) }
        }
    }

    performancesToImport?.let { selectedPerformances ->
        ConfirmActImportDialog(
            performances = selectedPerformances,
            onDismiss = {
                performancesToImport = null
                spreadsheetUrlToImport = null
            },
            onConfirm = {
                onGoogleSheetsSyncAll(selectedPerformances, requireNotNull(spreadsheetUrlToImport))
                performancesToImport = null
                spreadsheetUrlToImport = null
            }
        )
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

    if (showImportDialog) {
        SpreadsheetTransferDialog(
            sources = googleSheetSources,
            onDismiss = { showImportDialog = false },
            onTransfer = { spreadsheetUrl ->
                onGoogleSheetsTransfer(spreadsheetUrl)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun SpreadsheetTransferDialog(
    sources: List<GoogleSheetSource>,
    onDismiss: () -> Unit,
    onTransfer: (String) -> Unit
) {
    var spreadsheetUrl by rememberSaveable { mutableStateOf("") }
    var isSourceMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedSourceName by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_spreadsheet)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_spreadsheet_message))
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onTransfer(spreadsheetUrl) }, enabled = spreadsheetUrl.isNotBlank()) { Text(stringResource(R.string.import_spreadsheet)) }
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
    onSyncAct: (List<Performance>, GoogleSheetSource) -> Unit,
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
                        Row {
                            sourcesByAct[actName]?.let { source ->
                                val importablePerformances = actPerformances
                                    .filter(Performance::canSyncFromGoogleSheets)
                                if (importablePerformances.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onSyncAct(importablePerformances, source) }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Download,
                                            contentDescription = stringResource(R.string.import_act_data)
                                        )
                                    }
                                }
                            }
                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = null
                            )
                        }
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
private fun ConfirmActImportDialog(
    performances: List<Performance>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_act_data)) },
        text = {
            Text(
                stringResource(
                    R.string.import_act_data_confirmation,
                    performances.firstOrNull()?.actName.orEmpty(),
                    performances.size
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.import_spreadsheet))
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
