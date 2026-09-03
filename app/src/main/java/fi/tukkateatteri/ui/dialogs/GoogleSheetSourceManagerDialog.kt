package fi.tukkateatteri.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.GoogleSheetSource

@Composable
fun GoogleSheetSourceManagerDialog(
    sources: List<GoogleSheetSource>,
    onDismiss: () -> Unit,
    onSave: (actName: String, spreadsheetUrl: String) -> Unit,
    onDelete: (actName: String) -> Unit
) {
    var editingActName by rememberSaveable { mutableStateOf<String?>(null) }
    var isAddingSource by rememberSaveable { mutableStateOf(false) }
    var sourceToDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val sourceBeingEdited = sources.firstOrNull { it.actName == editingActName }
    if (isAddingSource || sourceBeingEdited != null) {
        GoogleSheetSourceEditorDialog(
            source = sourceBeingEdited,
            onDismiss = {
                editingActName = null
                isAddingSource = false
            },
            onSave = { actName, spreadsheetUrl ->
                onSave(actName, spreadsheetUrl)
                editingActName = null
                isAddingSource = false
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.manage_google_sheet_sources)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (sources.isEmpty()) {
                        Text(stringResource(R.string.no_google_sheet_sources))
                    } else {
                        sources.forEachIndexed { index, source ->
                            if (index > 0) HorizontalDivider()
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(source.actName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = source.spreadsheetUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editingActName = source.actName }) {
                                        Text(stringResource(R.string.edit))
                                    }
                                    TextButton(onClick = { sourceToDelete = source.actName }) {
                                        Text(
                                            text = stringResource(R.string.delete),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isAddingSource = true }) {
                    Text(stringResource(R.string.add_google_sheet_source))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    sourceToDelete?.let { actName ->
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = { Text(stringResource(R.string.delete_google_sheet_source_title)) },
            text = { Text(stringResource(R.string.delete_google_sheet_source_message, actName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(actName)
                        sourceToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { sourceToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun GoogleSheetSourceEditorDialog(
    source: GoogleSheetSource?,
    onDismiss: () -> Unit,
    onSave: (actName: String, spreadsheetUrl: String) -> Unit
) {
    var actName by rememberSaveable(source?.actName) { mutableStateOf(source?.actName.orEmpty()) }
    var spreadsheetUrl by rememberSaveable(source?.actName) { mutableStateOf(source?.spreadsheetUrl.orEmpty()) }
    val isExistingSource = source != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isExistingSource) R.string.edit_google_sheet_source else R.string.add_google_sheet_source
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = actName,
                    onValueChange = { actName = it },
                    label = { Text(stringResource(R.string.act_name)) },
                    enabled = !isExistingSource,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = spreadsheetUrl,
                    onValueChange = { spreadsheetUrl = it },
                    label = { Text(stringResource(R.string.google_sheet_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(actName, spreadsheetUrl) },
                enabled = actName.isNotBlank() && spreadsheetUrl.isNotBlank()
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
