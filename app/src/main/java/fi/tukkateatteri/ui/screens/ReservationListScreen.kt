package fi.tukkateatteri.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.Reservation
import fi.tukkateatteri.data.ReservationSyncState
import java.text.Collator
import java.util.Locale

private val FLOATING_ACTION_BUTTON_CLEARANCE = 88.dp
private val SYNC_UNDERCARD_HORIZONTAL_OFFSET = 40.dp
private val SYNC_CARD_BORDER_WIDTH = 1.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReservationListScreen(
    activePerformance: Performance?,
    reservations: List<Reservation>,
    onOpenPerformanceMenu: () -> Unit,
    onReservationClick: (Reservation) -> Unit,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onManageGoogleSheetSourcesClick: () -> Unit,
    onChangeGoogleAccountClick: () -> Unit,
    onSyncPendingClick: () -> Unit,
    onDeleteAllClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: (() -> Unit)?
) {
    val redeemedCount = reservations.count(Reservation::isFullyRedeemed)
    val totalSeatCount = reservations.sumOf(Reservation::seatCount)
    val pendingChangeCount = reservations.count { reservation ->
        reservation.syncState == ReservationSyncState.PENDING ||
            reservation.syncState == ReservationSyncState.PENDING_DELETION
    }
    var isDataMenuExpanded by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()
    val collator = remember { Collator.getInstance(FINNISH_LOCALE) }
    val sortedReservations = remember(reservations, collator) {
        reservations.sortedWith { first, second ->
            val syncStateComparison = first.syncState.sortPriority
                .compareTo(second.syncState.sortPriority)
            if (syncStateComparison != 0) {
                syncStateComparison
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    Column {
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
                },
                actions = {
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
                            if (pendingChangeCount > 0) {
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
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
        if (onRefresh == null) {
            ReservationListContent(
                reservations = sortedReservations,
                hasActivePerformance = activePerformance != null,
                onReservationClick = onReservationClick,
                modifier = contentModifier
            )
        } else {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = pullToRefreshState,
                modifier = contentModifier,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.TopCenter),
                        isRefreshing = isRefreshing,
                        state = pullToRefreshState,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            ) {
                ReservationListContent(
                    reservations = sortedReservations,
                    hasActivePerformance = activePerformance != null,
                    onReservationClick = onReservationClick,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ReservationListContent(
    reservations: List<Reservation>,
    hasActivePerformance: Boolean,
    onReservationClick: (Reservation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (reservations.isEmpty()) {
        EmptyReservationList(
            hasActivePerformance = hasActivePerformance,
            modifier = modifier
        )
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = FLOATING_ACTION_BUTTON_CLEARANCE
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = reservations,
                key = Reservation::id
            ) { reservation ->
                ReservationRow(
                    reservation = reservation,
                    onClick = { onReservationClick(reservation) }
                )
            }
        }
    }
}

private val FINNISH_LOCALE = Locale.forLanguageTag("fi-FI")

@Composable
private fun EmptyReservationList(
    hasActivePerformance: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (hasActivePerformance) R.string.no_reservations else R.string.select_performance
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    if (hasActivePerformance) {
                        R.string.no_reservations_description
                    } else {
                        R.string.no_active_performance
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReservationRow(
    reservation: Reservation,
    onClick: () -> Unit
) {
    val displayName = reservation.displayName.ifBlank {
        stringResource(R.string.admission_type_door_sale)
    }
    val backgroundColor = when {
        reservation.isCompleted -> MaterialTheme.colorScheme.tertiaryContainer
        reservation.isFullyRedeemed -> MaterialTheme.colorScheme.primaryContainer
        reservation.isPresent -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        reservation.isCompleted -> MaterialTheme.colorScheme.onTertiaryContainer
        reservation.isFullyRedeemed -> MaterialTheme.colorScheme.onPrimaryContainer
        reservation.isPresent -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val statusText = stringResource(
        R.string.payment_overview,
        reservation.paidSeatCount,
        reservation.seatCount
    )

    val syncUndercard = reservation.syncState.toSyncUndercard()
    if (syncUndercard == null) {
        ReservationForegroundCard(
            reservation = reservation,
            displayName = displayName,
            statusText = statusText,
            backgroundColor = backgroundColor,
            contentColor = contentColor,
            onClick = onClick
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            SyncStatusUndercard(
                undercard = syncUndercard,
                modifier = Modifier.matchParentSize(),
                onClick = onClick
            )
            ReservationForegroundCard(
                reservation = reservation,
                displayName = displayName,
                statusText = statusText,
                backgroundColor = backgroundColor,
                contentColor = contentColor,
                onClick = onClick,
                drawSyncBorder = true,
                modifier = Modifier.padding(start = SYNC_UNDERCARD_HORIZONTAL_OFFSET)
            )
        }
    }
}

@Composable
private fun ReservationForegroundCard(
    modifier: Modifier = Modifier,
    reservation: Reservation,
    displayName: String,
    statusText: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    drawSyncBorder: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        border = if (drawSyncBorder) {
            BorderStroke(
                width = SYNC_CARD_BORDER_WIDTH,
                color = contentColor.copy(alpha = FOREGROUND_CARD_BORDER_ALPHA)
            )
        } else {
            null
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (reservation.contact.isNotBlank()) {
                Text(
                    text = reservation.contact,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ReservationStatusRow(
                icon = Icons.Filled.ConfirmationNumber,
                text = stringResource(
                    R.string.reservation_summary,
                    pluralStringResource(
                        R.plurals.seat_count_summary,
                        reservation.seatCount,
                        reservation.seatCount
                    ),
                    statusText
                ),
                modifier = Modifier.padding(top = 8.dp),
                contentColor = contentColor
            )
            if (reservation.hasTicketValueMismatch) {
                ReservationStatusRow(
                    icon = Icons.Filled.WarningAmber,
                    text = stringResource(R.string.reservation_ticket_value_mismatch),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    contentColor = MaterialTheme.colorScheme.error
                )
            }
            if (reservation.arrivalCount > 0) {
                ReservationStatusRow(
                    icon = Icons.Filled.Person,
                    text = stringResource(
                        R.string.arrival_overview,
                        reservation.arrivalCount,
                        reservation.seatCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    contentColor = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SyncStatusUndercard(
    undercard: SyncUndercard,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = undercard.containerColor,
            contentColor = undercard.contentColor
        ),
        border = BorderStroke(
            width = SYNC_CARD_BORDER_WIDTH,
            color = undercard.contentColor.copy(alpha = UNDERCARD_BORDER_ALPHA)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = undercard.icon,
                contentDescription = undercard.contentDescription,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                tint = undercard.contentColor
            )
        }
    }
}

private val ReservationSyncState.sortPriority: SyncSortPriority
    get() = when (this) {
        ReservationSyncState.CONFLICT -> SyncSortPriority.CONFLICT
        ReservationSyncState.PENDING,
        ReservationSyncState.PENDING_DELETION -> SyncSortPriority.PENDING
        ReservationSyncState.SYNCED -> SyncSortPriority.SYNCED
    }

private enum class SyncSortPriority {
    CONFLICT,
    PENDING,
    SYNCED
}

@Composable
private fun ReservationSyncState.toSyncUndercard(): SyncUndercard? = when (this) {
    ReservationSyncState.SYNCED -> null
    ReservationSyncState.PENDING -> SyncUndercard(
        icon = Icons.Filled.CloudUpload,
        contentDescription = stringResource(R.string.sheet_change_pending),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    )
    ReservationSyncState.CONFLICT -> SyncUndercard(
        icon = Icons.Filled.WarningAmber,
        contentDescription = stringResource(R.string.sheet_change_conflict),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    )
    ReservationSyncState.PENDING_DELETION -> SyncUndercard(
        icon = Icons.Filled.DeleteOutline,
        contentDescription = stringResource(R.string.sheet_deletion_pending),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    )
}

private data class SyncUndercard(
    val icon: ImageVector,
    val contentDescription: String,
    val containerColor: Color,
    val contentColor: Color
)

private const val FOREGROUND_CARD_BORDER_ALPHA = 0.22f
private const val UNDERCARD_BORDER_ALPHA = 0.34f

@Composable
private fun ReservationStatusRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    contentColor: Color
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp),
            tint = contentColor
        )
        Text(
            text = text,
            style = style,
            color = contentColor
        )
    }
}
