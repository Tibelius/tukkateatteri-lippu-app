package fi.tukkateatteri

import androidx.annotation.StringRes
import fi.tukkateatteri.data.GoogleSheetChangePendingException
import fi.tukkateatteri.data.GoogleSheetSource
import fi.tukkateatteri.data.GoogleSheetSourceChangedException
import fi.tukkateatteri.data.NoGoogleSheetImportCandidatesException
import fi.tukkateatteri.data.Performance
import fi.tukkateatteri.data.ReservationRepository
import fi.tukkateatteri.data.spreadsheet.GoogleSheetLockedException
import fi.tukkateatteri.data.spreadsheet.SheetFieldClassification
import fi.tukkateatteri.data.spreadsheet.SheetFieldMapping
import fi.tukkateatteri.data.spreadsheet.UnmappedSheetColumnsException
import fi.tukkateatteri.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns Google Sheets operation state and keeps transfer concerns out of [ReservationViewModel]. */
internal class SheetSyncController(
    private val repository: ReservationRepository,
    private val scope: CoroutineScope
) {
    private val mutableTransferMessage = MutableStateFlow<UiMessage?>(null)
    private val mutableTransferInProgress = MutableStateFlow(false)
    private val mutableMappingRequest = MutableStateFlow<SheetMappingRequest?>(null)
    private val mutableSyncingPerformanceIds = MutableStateFlow<Set<Long>>(emptySet())
    private val mutationMutex = Mutex()
    private val backgroundRequests = mutableMapOf<Long, BackgroundSyncRequest>()
    private val backgroundJobs = mutableMapOf<Long, Job>()
    private val refreshThrottle = RefreshThrottle<Long>(AUTOMATIC_REFRESH_INTERVAL_MILLIS)
    private var pendingMappingOperation: PendingMappingOperation? = null
    private var activeOperationCount = 0

    val transferMessage: StateFlow<UiMessage?> = mutableTransferMessage
    val isTransferInProgress: StateFlow<Boolean> = mutableTransferInProgress
    val mappingRequest: StateFlow<SheetMappingRequest?> = mutableMappingRequest
    val syncingPerformanceIds: StateFlow<Set<Long>> = mutableSyncingPerformanceIds

    fun reserveAutomaticRefresh(performanceId: Long): Boolean {
        if (!refreshThrottle.tryAcquire(performanceId)) {
            AppLog.debug(LOG_COMPONENT) {
                "Skipping recent automatic refresh; performanceId=$performanceId"
            }
            return false
        }
        AppLog.debug(LOG_COMPONENT) { "Reserved automatic refresh; performanceId=$performanceId" }
        return true
    }

    fun launchTrackedOperation(operation: String, action: suspend () -> Unit) {
        scope.launch {
            val startedAt = System.nanoTime()
            AppLog.debug(LOG_COMPONENT) { "Starting $operation" }
            activeOperationCount += 1
            mutableTransferInProgress.value = true
            try {
                action()
                AppLog.debug(LOG_COMPONENT) {
                    "Completed $operation in ${AppLog.elapsedMillis(startedAt)} ms"
                }
            } catch (exception: CancellationException) {
                AppLog.debug(LOG_COMPONENT) {
                    "Cancelled $operation after ${AppLog.elapsedMillis(startedAt)} ms"
                }
                throw exception
            } finally {
                activeOperationCount -= 1
                mutableTransferInProgress.value = activeOperationCount > 0
                AppLog.verbose(LOG_COMPONENT) { "Active operations=$activeOperationCount" }
            }
        }
    }

    fun launchReservationMutation(
        operation: String,
        showProgress: Boolean = true,
        action: suspend () -> Unit
    ) {
        val guardedAction: suspend () -> Unit = {
            mutationMutex.withLock {
                try {
                    action()
                } catch (exception: GoogleSheetChangePendingException) {
                    AppLog.warning(LOG_COMPONENT, exception) {
                        "$operation was saved locally but could not be synchronized"
                    }
                } catch (exception: GoogleSheetLockedException) {
                    AppLog.warning(LOG_COMPONENT, exception) {
                        "$operation could not acquire the performance lock"
                    }
                    mutableTransferMessage.value = UiMessage.Text(
                        R.string.google_sheets_performance_locked
                    )
                } catch (exception: Exception) {
                    exception.rethrowIfCancellation()
                    AppLog.error(LOG_COMPONENT, exception) { "$operation failed" }
                    mutableTransferMessage.value = UiMessage.Text(
                        R.string.google_sheets_change_failed
                    )
                }
            }
        }
        if (showProgress) {
            launchTrackedOperation(operation, guardedAction)
        } else {
            scope.launch { guardedAction() }
        }
    }

    fun finishReservationEditing(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String?
    ) {
        if (accessToken == null) return
        refreshThrottle.mark(performanceId)
        backgroundRequests[performanceId] = BackgroundSyncRequest(spreadsheetUrl, accessToken)
        if (backgroundJobs[performanceId]?.isActive == true) return

        backgroundJobs[performanceId] = scope.launch {
            mutableSyncingPerformanceIds.value += performanceId
            try {
                flushBackgroundRequests(performanceId)
            } finally {
                backgroundJobs.remove(performanceId)
                mutableSyncingPerformanceIds.value -= performanceId
            }
        }
    }

    private suspend fun flushBackgroundRequests(performanceId: Long) {
        while (true) {
            delay(BACKGROUND_SYNC_DEBOUNCE_MILLIS)
            val request = backgroundRequests.remove(performanceId) ?: break
            mutationMutex.withLock { /* Wait for already queued local writes. */ }
            try {
                AppLog.info(LOG_COMPONENT) {
                    "Starting background Sheet flush; performanceId=$performanceId"
                }
                repository.syncGoogleSheetPerformance(
                    performanceId,
                    request.spreadsheetUrl,
                    request.accessToken
                )
                refreshThrottle.mark(performanceId)
                AppLog.info(LOG_COMPONENT) {
                    "Completed background Sheet flush; performanceId=$performanceId"
                }
            } catch (exception: UnmappedSheetColumnsException) {
                AppLog.info(LOG_COMPONENT) {
                    "Background Sheet flush needs ${exception.headers.size} column classifications; " +
                        "performanceId=$performanceId"
                }
                requestFieldMappings(
                    exception = exception,
                    spreadsheetUrl = request.spreadsheetUrl,
                    accessToken = request.accessToken,
                    failureMessageResId = R.string.google_sheets_sync_failed
                ) {
                    repository.syncGoogleSheetPerformance(
                        performanceId,
                        request.spreadsheetUrl,
                        request.accessToken
                    )
                    refreshThrottle.mark(performanceId)
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.warning(LOG_COMPONENT, exception) {
                    "Background Sheet flush failed; changes remain pending for performanceId=$performanceId"
                }
            }
            if (performanceId !in backgroundRequests) break
        }
    }

    fun prepareImport(spreadsheetUrl: String, accessToken: String) {
        launchTrackedOperation("import spreadsheet") {
            try {
                AppLog.info(LOG_COMPONENT) { "Starting spreadsheet import" }
                val result = repository.importGoogleSheet(spreadsheetUrl, accessToken)
                AppLog.info(LOG_COMPONENT) {
                    "Spreadsheet import completed; performances=${result.performanceCount}, " +
                        "reservations=${result.reservationCount}"
                }
                mutableTransferMessage.value = importSucceededMessage(
                    result.performanceCount,
                    result.reservationCount
                )
            } catch (exception: NoGoogleSheetImportCandidatesException) {
                AppLog.warning(LOG_COMPONENT, exception) {
                    "Spreadsheet contained no importable performances"
                }
                mutableTransferMessage.value = UiMessage.Text(
                    R.string.google_sheets_no_import_candidates
                )
            } catch (exception: UnmappedSheetColumnsException) {
                AppLog.info(LOG_COMPONENT) {
                    "Import needs ${exception.headers.size} column classifications"
                }
                requestFieldMappings(exception, spreadsheetUrl, accessToken) {
                    val result = repository.importGoogleSheet(spreadsheetUrl, accessToken)
                    mutableTransferMessage.value = importSucceededMessage(
                        result.performanceCount,
                        result.reservationCount
                    )
                }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "Spreadsheet import failed" }
                mutableTransferMessage.value = UiMessage.Text(R.string.google_sheets_import_failed)
            }
        }
    }

    fun applyFieldMappings(mappings: Map<String, SheetFieldClassification>) {
        val pending = pendingMappingOperation ?: return
        mutableMappingRequest.value = null
        pendingMappingOperation = null
        launchTrackedOperation("save Sheet field mappings") {
            try {
                repository.saveSheetFieldMappings(
                    pending.spreadsheetUrl,
                    pending.accessToken,
                    mappings.map { (header, classification) ->
                        SheetFieldMapping(header, classification)
                    }
                )
                pending.retry()
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) {
                    "Saving field mappings or retrying the original operation failed"
                }
                mutableTransferMessage.value = UiMessage.Text(pending.failureMessageResId)
            }
        }
    }

    fun dismissFieldMappingRequest() {
        pendingMappingOperation = null
        mutableMappingRequest.value = null
    }

    private fun requestFieldMappings(
        exception: UnmappedSheetColumnsException,
        spreadsheetUrl: String,
        accessToken: String,
        @StringRes failureMessageResId: Int = R.string.google_sheets_import_failed,
        retry: suspend () -> Unit
    ) {
        pendingMappingOperation = PendingMappingOperation(
            spreadsheetUrl = spreadsheetUrl,
            accessToken = accessToken,
            failureMessageResId = failureMessageResId,
            retry = retry
        )
        mutableMappingRequest.value = SheetMappingRequest(exception.headers)
    }

    fun syncPerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String,
        showFeedback: Boolean = true
    ) {
        refreshThrottle.mark(performanceId)
        val synchronize: suspend () -> Unit = {
            synchronizePerformance(performanceId, spreadsheetUrl, accessToken, showFeedback)
        }
        if (showFeedback) {
            launchTrackedOperation("synchronize performance", synchronize)
        } else {
            scope.launch { synchronize() }
        }
    }

    private suspend fun synchronizePerformance(
        performanceId: Long,
        spreadsheetUrl: String,
        accessToken: String,
        showFeedback: Boolean
    ) {
        try {
            AppLog.info(LOG_COMPONENT) {
                "Starting synchronization for performanceId=$performanceId"
            }
            val rowCount = repository.syncGoogleSheetPerformance(
                performanceId,
                spreadsheetUrl,
                accessToken
            )
            AppLog.info(LOG_COMPONENT) {
                "Synchronized performanceId=$performanceId; receivedRows=$rowCount"
            }
        } catch (exception: GoogleSheetSourceChangedException) {
            AppLog.warning(LOG_COMPONENT, exception) {
                "Performance source metadata no longer matches; performanceId=$performanceId"
            }
            if (showFeedback) {
                mutableTransferMessage.value = UiMessage.Text(
                    R.string.google_sheets_sync_source_changed
                )
            }
        } catch (exception: GoogleSheetLockedException) {
            AppLog.warning(LOG_COMPONENT, exception) {
                "Performance synchronization could not acquire lock; performanceId=$performanceId"
            }
            if (showFeedback) {
                mutableTransferMessage.value = UiMessage.Text(
                    R.string.google_sheets_performance_locked
                )
            }
        } catch (exception: UnmappedSheetColumnsException) {
            AppLog.info(LOG_COMPONENT) {
                "Performance synchronization needs ${exception.headers.size} column classifications; " +
                    "performanceId=$performanceId"
            }
            requestFieldMappings(
                exception = exception,
                spreadsheetUrl = spreadsheetUrl,
                accessToken = accessToken,
                failureMessageResId = R.string.google_sheets_sync_failed
            ) {
                val rowCount = repository.syncGoogleSheetPerformance(
                    performanceId,
                    spreadsheetUrl,
                    accessToken
                )
                AppLog.info(LOG_COMPONENT) {
                    "Synchronized performanceId=$performanceId after field classification; " +
                        "receivedRows=$rowCount"
                }
            }
        } catch (exception: Exception) {
            exception.rethrowIfCancellation()
            AppLog.error(LOG_COMPONENT, exception) {
                "Performance synchronization failed; performanceId=$performanceId"
            }
            if (showFeedback) {
                mutableTransferMessage.value = UiMessage.Text(R.string.google_sheets_sync_failed)
            }
        }
    }

    fun syncPerformances(
        performances: List<Performance>,
        spreadsheetUrl: String,
        accessToken: String
    ) {
        launchTrackedOperation("synchronize performances") {
            AppLog.info(LOG_COMPONENT) {
                "Starting synchronization for ${performances.size} performances"
            }
            try {
                reportBatchResult(
                    performances,
                    synchronizePerformances(performances, spreadsheetUrl, accessToken)
                )
            } catch (exception: UnmappedSheetColumnsException) {
                AppLog.info(LOG_COMPONENT) {
                    "Performance batch synchronization needs ${exception.headers.size} column classifications"
                }
                requestFieldMappings(
                    exception = exception,
                    spreadsheetUrl = spreadsheetUrl,
                    accessToken = accessToken,
                    failureMessageResId = R.string.google_sheets_sync_failed
                ) {
                    reportBatchResult(
                        performances,
                        synchronizePerformances(performances, spreadsheetUrl, accessToken)
                    )
                }
            }
        }
    }

    private suspend fun synchronizePerformances(
        performances: List<Performance>,
        spreadsheetUrl: String,
        accessToken: String
    ): List<String> = buildList {
        performances.forEach { performance ->
            try {
                repository.syncGoogleSheetPerformance(
                    performance.id,
                    spreadsheetUrl,
                    accessToken
                )
                refreshThrottle.mark(performance.id)
            } catch (exception: UnmappedSheetColumnsException) {
                throw exception
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) {
                    "Performance synchronization failed; performanceId=${performance.id}, " +
                        "date=${performance.date}"
                }
                add(performance.displayName)
            }
        }
    }

    private fun reportBatchResult(
        performances: List<Performance>,
        failedPerformances: List<String>
    ) {
        if (failedPerformances.isEmpty()) {
            AppLog.info(LOG_COMPONENT) {
                "Performance batch synchronization completed; performances=${performances.size}"
            }
            return
        }
        AppLog.warning(LOG_COMPONENT) {
            "Performance batch synchronization completed with ${failedPerformances.size} failures"
        }
        mutableTransferMessage.value = UiMessage.Text(
            messageResId = R.string.google_sheets_sync_dates_failed,
            formatArgs = listOf(failedPerformances.joinToString())
        )
    }

    fun saveSource(actName: String, spreadsheetUrl: String) {
        launchSourceMutation(
            operation = "save spreadsheet source",
            action = {
                repository.upsertGoogleSheetSource(GoogleSheetSource(actName, spreadsheetUrl))
            }
        )
    }

    fun deleteSource(actName: String) {
        launchSourceMutation(
            operation = "delete spreadsheet source",
            action = { repository.deleteGoogleSheetSource(actName) }
        )
    }

    private fun launchSourceMutation(operation: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                AppLog.info(LOG_COMPONENT) { "Completed $operation" }
            } catch (exception: Exception) {
                exception.rethrowIfCancellation()
                AppLog.error(LOG_COMPONENT, exception) { "$operation failed" }
                mutableTransferMessage.value = UiMessage.Text(
                    R.string.google_sheets_source_save_failed
                )
            }
        }
    }

    fun dismissTransferMessage() {
        mutableTransferMessage.value = null
    }

    private fun importSucceededMessage(performanceCount: Int, reservationCount: Int) =
        UiMessage.Plural(
            messageResId = R.plurals.google_sheets_import_succeeded,
            quantity = performanceCount,
            formatArgs = listOf(performanceCount, reservationCount)
        )

    private companion object {
        const val LOG_COMPONENT = "SheetSync"
        const val BACKGROUND_SYNC_DEBOUNCE_MILLIS = 200L
        const val AUTOMATIC_REFRESH_INTERVAL_MILLIS = 2 * 60 * 1_000L
    }
}
