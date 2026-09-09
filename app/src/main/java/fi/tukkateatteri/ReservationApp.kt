package fi.tukkateatteri

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.ui.dialogs.AddAdmissionDialog
import fi.tukkateatteri.ui.dialogs.AddAdmissionTypeDialog
import fi.tukkateatteri.ui.dialogs.DeleteAllReservationsDialog
import fi.tukkateatteri.ui.dialogs.DeleteReservationDialog
import fi.tukkateatteri.ui.dialogs.GoogleSheetSourceManagerDialog
import fi.tukkateatteri.ui.dialogs.ReservationDialog
import fi.tukkateatteri.ui.screens.ReservationListScreen
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@Composable
internal fun ReservationApp(
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
    val pullToRefreshAction = activePerformance
        ?.takeIf(Performance::canSyncFromGoogleSheets)
        ?.let { performance ->
            googleSheetSources
                .firstOrNull { source -> source.actName == performance.actName }
                ?.let { source ->
                    { onGoogleSheetsSync(performance, source.spreadsheetUrl, true) }
                }
        }

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
        Box(modifier = Modifier.fillMaxSize()) {
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
                onDeleteAllClick = { showDeleteAllReservationsConfirmation = true },
                isRefreshing = isTransferInProgress,
                onRefresh = pullToRefreshAction
            )
            if (isTransferInProgress) {
                TransferProgressOverlay(showSpinner = pullToRefreshAction == null)
            }
        }
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
            text = {
                Text(
                    when (message) {
                        is UiMessage.Text -> stringResource(
                            message.messageResId,
                            *message.formatArgs.toTypedArray()
                        )
                        is UiMessage.Plural -> pluralStringResource(
                            message.messageResId,
                            message.quantity,
                            *message.formatArgs.toTypedArray()
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTransferMessage) {
                    Text(stringResource(R.string.close))
                }
            }
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
