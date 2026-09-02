package fi.tukkateatteri.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fi.tukkateatteri.R
import fi.tukkateatteri.data.AdmissionType

@Composable
fun AddAdmissionTypeDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (AdmissionType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_admission_title)) },
        text = { Text(stringResource(R.string.add_admission_description)) },
        confirmButton = {
            TextButton(onClick = { onTypeSelected(AdmissionType.RESERVATION) }) {
                Text(stringResource(R.string.add_reservation))
            }
        },
        dismissButton = {
            TextButton(onClick = { onTypeSelected(AdmissionType.DOOR_SALE) }) {
                Text(stringResource(R.string.add_door_sale))
            }
        }
    )
}
