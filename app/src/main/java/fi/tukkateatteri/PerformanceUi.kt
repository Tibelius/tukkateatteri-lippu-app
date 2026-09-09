package fi.tukkateatteri

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.toPerformanceDateOrNull
import java.time.LocalDate

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
                            Text(
                                selectedSourceName
                                    ?: stringResource(R.string.select_saved_google_sheet)
                            )
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun TransferProgressOverlay(showSpinner: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = TRANSFER_SCRIM_ALPHA))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {}
            )
    ) {
        if (showSpinner) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = TRANSFER_PROGRESS_TOP_OFFSET),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 4.dp
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(12.dp)
                        .size(32.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private const val TRANSFER_SCRIM_ALPHA = 0.16f
private val TRANSFER_PROGRESS_TOP_OFFSET = 72.dp

@Composable
internal fun PerformanceDrawerContent(
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
                        Text(
                            pluralStringResource(
                                R.plurals.performance_date_count,
                                actPerformances.size,
                                actPerformances.size
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            hasExplicitExpansionSelection = true
                            expandedActName = if (isExpanded) null else actName
                        },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
        colors = ListItemDefaults.colors(
            containerColor = if (performance.isActive) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    )
}

@Composable
internal fun DeletePerformanceDialog(
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
internal fun ConfirmActImportDialog(
    performances: List<Performance>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_act_data)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.import_act_data_confirmation,
                    performances.size,
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
internal fun PerformanceEditorDialog(
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
