package fi.tukkateatteri

import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.PerformanceStatistics
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.StatisticsReport
import fi.tukkateatteri.data.calculateSalesStatistics
import fi.tukkateatteri.data.toPerformanceDateOrNull
import fi.tukkateatteri.logging.AppLog
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

internal class StatisticsState(
    private val repository: ReservationRepository,
    scope: CoroutineScope
) {
    private val target = MutableStateFlow<StatisticsTarget?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val report: StateFlow<StatisticsReport?> = target
        .flatMapLatest { selectedTarget ->
            when (selectedTarget) {
                null -> flowOf(null)
                is StatisticsTarget.PerformanceTarget -> performanceReport(selectedTarget.performanceId)
                is StatisticsTarget.ActTarget -> actReport(selectedTarget.actName)
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null
        )

    fun openPerformance(performanceId: Long) {
        AppLog.debug(LOG_COMPONENT) { "Opening performance statistics; performanceId=$performanceId" }
        target.value = StatisticsTarget.PerformanceTarget(performanceId)
    }

    fun openAct(actName: String) {
        AppLog.debug(LOG_COMPONENT) { "Opening act statistics; act=$actName" }
        target.value = StatisticsTarget.ActTarget(actName)
    }

    fun close() {
        target.value = null
    }

    private fun performanceReport(performanceId: Long) = combine(
        repository.performances,
        repository.reservationsForPerformance(performanceId)
    ) { performances, reservations ->
        performances.firstOrNull { it.id == performanceId }?.let { performance ->
            StatisticsReport(
                title = performance.displayName,
                isActSummary = false,
                statistics = calculateSalesStatistics(reservations)
            )
        }
    }

    private fun actReport(actName: String) = combine(
        repository.performances,
        repository.reservationsForAct(actName)
    ) { performances, reservations ->
        val actPerformances = performances
            .filter { it.actName == actName }
            .sortedWith(
                compareBy<Performance> { it.date.toPerformanceDateOrNull() ?: LocalDate.MAX }
                    .thenBy(Performance::date)
            )
        StatisticsReport(
            title = actName,
            isActSummary = true,
            statistics = calculateSalesStatistics(reservations),
            performances = actPerformances.map { performance ->
                PerformanceStatistics(
                    performance = performance,
                    statistics = calculateSalesStatistics(
                        reservations.filter { it.performanceId == performance.id }
                    )
                )
            }
        )
    }

    private sealed interface StatisticsTarget {
        data class PerformanceTarget(val performanceId: Long) : StatisticsTarget
        data class ActTarget(val actName: String) : StatisticsTarget
    }

    private companion object {
        const val LOG_COMPONENT = "Statistics"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
