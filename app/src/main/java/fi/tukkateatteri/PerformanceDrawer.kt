package fi.tukkateatteri

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
internal fun PerformanceDrawerContent(
    performances: List<Performance>,
    googleSheetSources: List<GoogleSheetSource>,
    onSelectPerformance: (Performance) -> Unit,
    onAddPerformance: () -> Unit,
    onSyncPerformance: (Performance, GoogleSheetSource) -> Unit,
    onSyncAct: (List<Performance>, GoogleSheetSource) -> Unit,
    onOpenActStatistics: (String) -> Unit,
    onDeletePerformance: (Performance) -> Unit,
    onDeleteAct: (String) -> Unit,
    onClearAll: () -> Unit
) {
    var expandedActName by rememberSaveable { mutableStateOf<String?>(null) }
    var hasExplicitExpansionSelection by rememberSaveable { mutableStateOf(false) }
    val performancesByAct = performances.groupBy(Performance::actName).toSortedMap(String.CASE_INSENSITIVE_ORDER)
    val sourcesByAct = googleSheetSources.associateBy(GoogleSheetSource::actName)

    ModalDrawerSheet {
        Column(
            modifier = Modifier.padding(vertical = 16.dp).verticalScroll(rememberScrollState())
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
                    leadingContent = {
                        Icon(
                            imageVector = if (isExpanded) {
                                Icons.Filled.ExpandLess
                            } else {
                                Icons.Filled.ExpandMore
                            },
                            contentDescription = null
                        )
                    },
                    supportingContent = {
                        Text(
                            pluralStringResource(
                                R.plurals.performance_date_count,
                                actPerformances.size,
                                actPerformances.size
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().clickable(
                        onClickLabel = stringResource(R.string.toggle_performance_dates),
                        onClick = {
                            hasExplicitExpansionSelection = true
                            expandedActName = if (isExpanded) null else actName
                        }
                    ),
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onOpenActStatistics(actName) }) {
                                Icon(
                                    imageVector = Icons.Filled.Assessment,
                                    contentDescription = stringResource(R.string.open_statistics)
                                )
                            }
                            sourcesByAct[actName]?.let { source ->
                                val importablePerformances = actPerformances.filter(Performance::canSyncFromGoogleSheets)
                                if (importablePerformances.isNotEmpty()) {
                                    IconButton(onClick = { onSyncAct(importablePerformances, source) }) {
                                        Icon(
                                            imageVector = Icons.Filled.Download,
                                            contentDescription = stringResource(R.string.import_act_data)
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { onDeleteAct(actName) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    stringResource(R.string.delete_act_action),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
                if (isExpanded) {
                    actPerformances
                        .sortedWith(
                            compareBy<Performance> { it.date.toPerformanceDateOrNull() ?: LocalDate.MAX }
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
            HorizontalDivider()
            OutlinedButton(
                onClick = onClearAll,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Text(stringResource(R.string.clear_all_local_data))
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp).clickable(onClick = onSelect),
        trailingContent = {
            Row {
                if (performance.canSyncFromGoogleSheets && source != null) {
                    IconButton(onClick = { onSync(source) }) {
                        Icon(Icons.Filled.Sync, stringResource(R.string.sync_google_sheet))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        stringResource(R.string.delete_performance_action),
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
