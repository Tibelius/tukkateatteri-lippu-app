package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fi.tukkateatteri.R
import fi.tukkateatteri.data.spreadsheet.SheetFieldClassification
import fi.tukkateatteri.ui.components.CancelSaveActions
import fi.tukkateatteri.ui.components.ScrollableAppDialog

@Composable
fun SheetFieldMappingDialog(
    headers: List<String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, SheetFieldClassification>) -> Unit
) {
    var mappings by rememberSaveable(headers) {
        mutableStateOf(headers.associateWith { "" })
    }
    ScrollableAppDialog(
        onDismissRequest = onDismiss,
        actions = {
            CancelSaveActions(
                onCancel = onDismiss,
                onSave = {
                    onSave(mappings.mapValues { SheetFieldClassification.valueOf(it.value) })
                },
                saveEnabled = mappings.values.all(String::isNotBlank)
            )
        }
    ) {
        Text(stringResource(R.string.sheet_field_mapping_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sheet_field_mapping_description))
        headers.forEach { header ->
            SheetFieldMappingRow(
                header = header,
                selected = mappings.getValue(header).takeIf(String::isNotBlank)
                    ?.let(SheetFieldClassification::valueOf),
                onSelected = { mappings = mappings + (header to it.name) }
            )
        }
    }
}

@Composable
private fun SheetFieldMappingRow(
    header: String,
    selected: SheetFieldClassification?,
    onSelected: (SheetFieldClassification) -> Unit
) {
    var expanded by rememberSaveable(header) { mutableStateOf(false) }
    Text(header, style = MaterialTheme.typography.titleMedium)
    Box {
        Button(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.label() ?: stringResource(R.string.sheet_field_select))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SheetFieldClassification.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label()) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SheetFieldClassification.label(): String = stringResource(
    when (this) {
        SheetFieldClassification.TICKET -> R.string.sheet_field_ticket
        SheetFieldClassification.PAYMENT -> R.string.sheet_field_payment
        SheetFieldClassification.IGNORE -> R.string.sheet_field_ignore
    }
)
