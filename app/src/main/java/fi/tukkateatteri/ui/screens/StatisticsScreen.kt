package fi.tukkateatteri.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R
import fi.tukkateatteri.data.PerformanceStatistics
import fi.tukkateatteri.data.SalesStatistics
import fi.tukkateatteri.data.StatisticsReport
import fi.tukkateatteri.data.toEuroString
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsScreen(
    report: StatisticsReport,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(report.title)
                        Text(
                            text = stringResource(
                                if (report.isActSummary) {
                                    R.string.statistics_act_summary
                                } else {
                                    R.string.statistics_performance_summary
                                }
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.padding(contentPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { AttendanceStatisticsCard(report.statistics) }
            item { RevenueStatisticsCard(report.statistics) }
            if (report.isActSummary && report.performances.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.statistics_by_performance)) }
                items(report.performances, key = { it.performance.id }) { performance ->
                    PerformanceStatisticsRow(
                        performance = performance
                    )
                }
            }
            if (report.statistics.ticketTypes.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.statistics_ticket_types)) }
                item {
                    StatisticsTableCard {
                        report.statistics.ticketTypes.forEachIndexed { index, ticketType ->
                            if (index > 0) HorizontalDivider()
                            StatisticsValueRow(
                                label = ticketType.label,
                                value = stringResource(
                                    R.string.statistics_quantity_and_money,
                                    ticketType.quantity,
                                    ticketType.revenueCents.toEuroString()
                                )
                            )
                        }
                    }
                }
            }
            if (report.statistics.paymentMethods.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.statistics_payment_methods)) }
                item {
                    StatisticsTableCard {
                        report.statistics.paymentMethods.forEachIndexed { index, paymentMethod ->
                            if (index > 0) HorizontalDivider()
                            StatisticsValueRow(
                                label = paymentMethod.label,
                                value = stringResource(
                                    R.string.statistics_transactions_and_money,
                                    paymentMethod.transactionCount,
                                    paymentMethod.amountCents.toEuroString()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttendanceStatisticsCard(statistics: SalesStatistics) {
    val percentFormatter = remember {
        NumberFormat.getPercentInstance(FINNISH_LOCALE).apply {
            maximumFractionDigits = 1
        }
    }
    StatisticsTableCard {
        Text(
            text = stringResource(R.string.statistics_attendance),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_reservations),
            value = statistics.reservationCount.toString()
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_door_sales),
            value = statistics.doorSaleCount.toString()
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_total_seats),
            value = statistics.seatCount.toString()
        )
        StatisticsProgressRow(
            label = stringResource(R.string.statistics_redeemed),
            current = statistics.redeemedSeatCount,
            total = statistics.seatCount,
            progress = statistics.redemptionRate,
            percentage = percentFormatter.format(statistics.redemptionRate)
        )
        StatisticsProgressRow(
            label = stringResource(R.string.statistics_arrived),
            current = statistics.arrivalCount,
            total = statistics.seatCount,
            progress = statistics.arrivalRate,
            percentage = percentFormatter.format(statistics.arrivalRate)
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_transactions),
            value = statistics.transactionCount.toString()
        )
    }
}

@Composable
private fun RevenueStatisticsCard(statistics: SalesStatistics) {
    StatisticsTableCard {
        Text(
            text = stringResource(R.string.statistics_revenue),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_total_revenue),
            value = statistics.revenueCents.toEuroString(),
            emphasized = true
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_reservation_revenue),
            value = statistics.reservationRevenueCents.toEuroString()
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_door_revenue),
            value = statistics.doorSaleRevenueCents.toEuroString()
        )
        StatisticsValueRow(
            label = stringResource(R.string.statistics_expected_revenue),
            value = statistics.expectedReservationRevenueCents.toEuroString()
        )
        if (statistics.canCompareReservationRevenue) {
            StatisticsValueRow(
                label = stringResource(R.string.statistics_revenue_difference),
                value = statistics.reservationRevenueDifferenceCents.toSignedEuroString()
            )
        } else if (!statistics.hasCompleteRevenueExpectation) {
            WarningRow(
                stringResource(
                    R.string.statistics_incomplete_expectation,
                    statistics.expectedReservationSeatCount,
                    statistics.reservationSeatCount
                )
            )
        }
        if (statistics.unknownRevenueSeatCount > 0) {
            WarningRow(
                stringResource(
                    R.string.statistics_unknown_prices,
                    statistics.unknownRevenueSeatCount
                )
            )
        }
    }
}

@Composable
private fun PerformanceStatisticsRow(
    performance: PerformanceStatistics
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        StatisticsValueRow(
            label = performance.performance.date,
            value = stringResource(
                R.string.statistics_performance_row,
                performance.statistics.redeemedSeatCount,
                performance.statistics.seatCount,
                performance.statistics.revenueCents.toEuroString()
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun StatisticsProgressRow(
    label: String,
    current: Int,
    total: Int,
    progress: Float,
    percentage: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatisticsValueRow(label, "$current / $total · $percentage")
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatisticsTableCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun StatisticsValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun WarningRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun Int.toSignedEuroString(): String =
    when {
        this > 0 -> "+${toEuroString()}"
        this < 0 -> "−${absoluteValue.toEuroString()}"
        else -> toEuroString()
    }

private val FINNISH_LOCALE = Locale.forLanguageTag("fi-FI")
