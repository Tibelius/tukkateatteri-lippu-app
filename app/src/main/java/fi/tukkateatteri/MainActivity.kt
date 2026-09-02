package fi.tukkateatteri

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.tukkateatteri.data.AdmissionType
import fi.tukkateatteri.ui.dialogs.AddAdmissionDialog
import fi.tukkateatteri.ui.dialogs.AddAdmissionTypeDialog
import fi.tukkateatteri.ui.dialogs.DeleteAllReservationsDialog
import fi.tukkateatteri.ui.dialogs.DeleteReservationDialog
import fi.tukkateatteri.ui.dialogs.ReservationDialog
import fi.tukkateatteri.ui.screens.ReservationListScreen
import fi.tukkateatteri.ui.theme.TukkateatteriTheme

class MainActivity : ComponentActivity() {
    private val reservationViewModel: ReservationViewModel by viewModels {
        ReservationViewModel.factory(
            (application as TukkateatteriApplication).reservationRepository
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TukkateatteriTheme {
                ReservationApp(reservationViewModel)
            }
        }
    }
}

@Composable
private fun ReservationApp(viewModel: ReservationViewModel) {
    val reservations by viewModel.reservations.collectAsStateWithLifecycle()
    var selectedReservationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reservationToDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAdmissionTypeDialog by rememberSaveable { mutableStateOf(false) }
    var selectedAdmissionTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllReservationsConfirmation by rememberSaveable { mutableStateOf(false) }
    var spreadsheetAction by rememberSaveable { mutableStateOf<SpreadsheetAction?>(null) }

    ReservationListScreen(
        reservations = reservations,
        onReservationClick = { reservation -> selectedReservationId = reservation.id },
        onAddClick = { showAdmissionTypeDialog = true },
        onImportClick = { spreadsheetAction = SpreadsheetAction.IMPORT },
        onExportClick = { spreadsheetAction = SpreadsheetAction.EXPORT },
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
            onSave = { lastName, firstName, contact, seatCount, paymentMethod ->
                viewModel.addAdmission(
                    lastName = lastName,
                    firstName = firstName,
                    contact = contact,
                    seatCount = seatCount,
                    admissionType = admissionType,
                    paymentMethod = paymentMethod
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

    spreadsheetAction?.let { action ->
        SpreadsheetTransferDialog(
            action = action,
            onDismiss = { spreadsheetAction = null }
        )
    }
}

@Composable
private fun SpreadsheetTransferDialog(
    action: SpreadsheetAction,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(action.titleResId)) },
        text = { Text(stringResource(action.messageResId)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
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
