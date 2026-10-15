package fi.tukkateatteri.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import fi.tukkateatteri.R
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState
import java.text.Collator
import java.util.Locale

internal val FLOATING_ACTION_BUTTON_CLEARANCE = 88.dp
internal val SYNC_UNDERCARD_HORIZONTAL_OFFSET = 40.dp
internal val SYNC_CARD_BORDER_WIDTH = 1.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationListScreen(
    activePerformance: Performance?,
    reservations: List<Reservation>,
    snackbarHostState: SnackbarHostState,
    onOpenPerformanceMenu: () -> Unit,
    onOpenStatistics: () -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onManageGoogleSheetSourcesClick: () -> Unit,
    onChangeGoogleAccountClick: () -> Unit,
    onSyncPendingClick: () -> Unit,
    onDeleteAllClick: () -> Unit,
    isBackgroundSyncInProgress: Boolean,
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?
) {
    val redeemedCount = reservations.count(Reservation::isFullyRedeemed)
    val totalSeatCount = reservations.sumOf(Reservation::seatCount)
    val pendingChangeCount = reservations.count { reservation ->
        reservation.syncState == ReservationSyncState.PENDING ||
            reservation.syncState == ReservationSyncState.PENDING_DELETION
    }
    val synchronizationIssueCount = reservations.count { reservation ->
        reservation.syncState != ReservationSyncState.SYNCED
    }
    var isDataMenuExpanded by remember { mutableStateOf(false) }
    var selectedFilter by rememberSaveable(activePerformance?.id) { mutableStateOf(ReservationFilter.ALL) }
    val incompleteCount = reservations.count(ReservationFilter.INCOMPLETE::includes)
    val pullToRefreshState = rememberPullToRefreshState()
    val collator = remember { Collator.getInstance(FINNISH_LOCALE) }
    val sortedReservations = remember(reservations, collator) {
        reservations.sortedWith { first, second ->
            val conflictComparison = first.syncState.conflictSortPriority
                .compareTo(second.syncState.conflictSortPriority)
            if (conflictComparison != 0) {
                conflictComparison
            } else {
                val lastNameComparison = collator.compare(first.lastName, second.lastName)
                if (lastNameComparison != 0) {
                    lastNameComparison
                } else {
                    collator.compare(first.firstName, second.firstName)
                }
            }
        }
    }

    val visibleReservations = sortedReservations.filter(selectedFilter::includes)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenPerformanceMenu) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.open_performance_menu)
                        )
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.clickable(
                            enabled = activePerformance != null,
                            onClick = onOpenStatistics
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = activePerformance?.displayName
                                    ?: stringResource(R.string.select_performance)
                            )
                            Text(
                                text = stringResource(
                                    R.string.reservation_overview,
                                    pluralStringResource(
                                        R.plurals.reservation_progress,
                                        reservations.size,
                                        redeemedCount,
                                        reservations.size
                                    ),
                                    pluralStringResource(
                                        R.plurals.total_seat_count,
                                        totalSeatCount,
                                        totalSeatCount
                                    )
                                ),
                                style = MaterialTheme.typography.titleSmall
                            )
                            if (pendingChangeCount > 0) {
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.pending_sheet_changes,
                                        pendingChangeCount,
                                        pendingChangeCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (activePerformance != null) {
                            Icon(
                                imageVector = Icons.Filled.Assessment,
                                contentDescription = stringResource(R.string.open_statistics),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (synchronizationIssueCount > 0) {
                        IconButton(
                            onClick = onSyncPendingClick,
                            enabled = !isBackgroundSyncInProgress && !isRefreshing
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = stringResource(R.string.sync_pending_changes)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { isDataMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_actions)
                            )
                        }
                        DropdownMenu(
                            expanded = isDataMenuExpanded,
                            onDismissRequest = { isDataMenuExpanded = false }
                        ) {
                            if (synchronizationIssueCount > 0) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sync_pending_changes)) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Sync, contentDescription = null)
                                    },
                                    onClick = {
                                        isDataMenuExpanded = false
                                        onSyncPendingClick()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_spreadsheet)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onImportClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.manage_google_sheet_sources)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onManageGoogleSheetSourcesClick()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.change_google_account)) },
                                onClick = {
                                    isDataMenuExpanded = false
                                    onChangeGoogleAccountClick()
                                }
                            )
                            if (reservations.isNotEmpty()) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.delete_all_reservations_action),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        isDataMenuExpanded = false
                                        onDeleteAllClick()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_admission_title)
                )
            }
        }
    ) { contentPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            if (activePerformance != null) {
                ReservationFilterSelector(
                    selected = selectedFilter,
                    totalCount = reservations.size,
                    incompleteCount = incompleteCount,
                    onSelect = { selectedFilter = it }
                )
            }
            val listModifier = Modifier.weight(1f).fillMaxWidth()
            val listContent: @Composable () -> Unit = {
                ReservationListContent(
                    reservations = visibleReservations,
                    hasActivePerformance = activePerformance != null,
                    isBackgroundSyncInProgress = isBackgroundSyncInProgress,
                    onReservationClick = onReservationClick,
                    isFiltered = selectedFilter != ReservationFilter.ALL && reservations.isNotEmpty(),
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (onRefresh == null) {
                Box(modifier = listModifier) { listContent() }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    state = pullToRefreshState,
                    modifier = listModifier,
                    indicator = {
                        if (!isRefreshing) {
                            PullToRefreshDefaults.Indicator(
                                modifier = Modifier.align(Alignment.TopCenter),
                                isRefreshing = false,
                                state = pullToRefreshState,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                ) {
                    listContent()
                }
            }
        }
    }
}
private val FINNISH_LOCALE = Locale.forLanguageTag("fi-FI")
