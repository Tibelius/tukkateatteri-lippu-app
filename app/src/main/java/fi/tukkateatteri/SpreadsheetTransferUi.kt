package fi.tukkateatteri

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.data.GoogleSheetSource

@Composable
internal fun SpreadsheetTransferDialog(
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
            TextButton(
                onClick = { onTransfer(spreadsheetUrl) },
                enabled = spreadsheetUrl.isNotBlank()
            ) {
                Text(stringResource(R.string.import_spreadsheet))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
internal fun TransferProgressOverlay() {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = TRANSFER_SCRIM_ALPHA))
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
    ) {
        TransferProgressIndicator(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = TRANSFER_PROGRESS_TOP_OFFSET)
        )
    }
}

@Composable
internal fun TransferProgressIndicator(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 4.dp
    ) {
        CircularProgressIndicator(
            modifier = Modifier.padding(12.dp).size(32.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private const val TRANSFER_SCRIM_ALPHA = 0.16f
private val TRANSFER_PROGRESS_TOP_OFFSET = 72.dp
