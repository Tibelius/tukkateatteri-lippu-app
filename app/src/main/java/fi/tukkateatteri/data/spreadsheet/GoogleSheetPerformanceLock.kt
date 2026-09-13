package fi.tukkateatteri.data.spreadsheet

import fi.tukkateatteri.logging.AppLog
import fi.tukkateatteri.logging.toAbbreviatedId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

internal class GoogleSheetPerformanceLock(
    private val api: GoogleSheetsApiClient,
    private val deviceId: String
) {
    private val localLocks = KeyedMutex()

    suspend fun <T> withLock(
        spreadsheetUrl: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T = withContext(Dispatchers.IO) {
        val spreadsheetId = spreadsheetUrl.toSpreadsheetId()
        api.ensureApplicationSheet(spreadsheetId, accessToken)
        val performanceKey = performanceLockKey(spreadsheetId, sheetTitle)
        AppLog.debug(LOG_COMPONENT) { "Waiting for local performance lock; tab=$sheetTitle" }
        localLocks.withLock(performanceKey) {
            AppLog.debug(LOG_COMPONENT) { "Entered local performance lock; tab=$sheetTitle" }
            withRemoteLock(spreadsheetId, performanceKey, sheetTitle, accessToken, action)
        }
    }

    private suspend fun <T> withRemoteLock(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        accessToken: String,
        action: suspend () -> T
    ): T {
        var lockId = UUID.randomUUID().toString()
        val now = Instant.now()
        AppLog.info(LOG_COMPONENT) { "Acquiring remote performance lock; tab=$sheetTitle" }
        var lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        if (removeExpiredLocks(spreadsheetId, lockTable, now, accessToken)) {
            AppLog.debug(LOG_COMPONENT) {
                "Removed expired lock rows before acquisition; tab=$sheetTitle"
            }
            lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        }
        val existingLock = lockTable.activeLockFor(performanceKey, now)
        if (existingLock != null) {
            if (existingLock.deviceId != deviceId) {
                AppLog.warning(LOG_COMPONENT) {
                    "Remote lock is held by another device; tab=$sheetTitle, holder=${existingLock.deviceId}"
                }
                throw GoogleSheetLockedException(
                    failure = GoogleSheetLockFailure.HELD_BY_ANOTHER_DEVICE,
                    sheetTitle = sheetTitle,
                    holderDeviceId = existingLock.deviceId
                )
            }
            lockId = existingLock.lockId.ifBlank { lockId }
            AppLog.debug(LOG_COMPONENT) {
                "Reusing this device's remote lock; tab=$sheetTitle, " +
                    "lockId=${lockId.toAbbreviatedId()}"
            }
        }

        writeLock(
            spreadsheetId = spreadsheetId,
            lockTable = lockTable,
            performanceKey = performanceKey,
            sheetTitle = sheetTitle,
            lockId = lockId,
            rowNumber = existingLock?.rowNumber ?: lockTable.firstAvailableRowNumber(),
            lockedAt = now,
            accessToken = accessToken,
            isExistingLock = existingLock != null
        )
        confirmOwnership(spreadsheetId, performanceKey, sheetTitle, lockId, accessToken)

        try {
            return coroutineScope {
                val renewalJob = launch {
                    while (isActive) {
                        delay(LOCK_RENEWAL_INTERVAL_MILLIS)
                        renewLock(
                            spreadsheetId,
                            performanceKey,
                            sheetTitle,
                            lockId,
                            accessToken
                        )
                    }
                }
                try {
                    action()
                } finally {
                    renewalJob.cancelAndJoin()
                }
            }
        } finally {
            releaseSafely(spreadsheetId, performanceKey, sheetTitle, lockId, accessToken)
        }
    }

    private fun writeLock(
        spreadsheetId: String,
        lockTable: LockTable,
        performanceKey: String,
        sheetTitle: String,
        lockId: String,
        rowNumber: Int,
        lockedAt: Instant,
        accessToken: String,
        isExistingLock: Boolean
    ) {
        AppLog.debug(LOG_COMPONENT) {
            val operation = if (isExistingLock) "Refreshing existing" else "Writing"
            "$operation remote lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
        }
        api.updateCells(
            spreadsheetId = spreadsheetId,
            accessToken = accessToken,
            values = lockTable.valuesFor(
                rowNumber = rowNumber,
                performanceKey = performanceKey,
                lockId = lockId,
                lockedAt = lockedAt.toString(),
                expiresAt = lockedAt.plusSeconds(LOCK_DURATION_SECONDS).toString(),
                deviceId = deviceId
            )
        )
    }

    private fun confirmOwnership(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        lockId: String,
        accessToken: String
    ) {
        val confirmedLock = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken)
            .toLockTable()
            .activeLockFor(performanceKey, Instant.now())
        if (confirmedLock?.lockId != lockId || confirmedLock.deviceId != deviceId) {
            AppLog.warning(LOG_COMPONENT) {
                "Remote lock acquisition could not be confirmed; tab=$sheetTitle, " +
                    "expected=${lockId.toAbbreviatedId()}, " +
                    "actual=${confirmedLock?.lockId?.toAbbreviatedId() ?: "none"}"
            }
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.ACQUISITION_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = confirmedLock?.deviceId
            )
        }
        AppLog.info(LOG_COMPONENT) {
            "Acquired remote performance lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
        }
    }

    private fun removeExpiredLocks(
        spreadsheetId: String,
        lockTable: LockTable,
        now: Instant,
        accessToken: String
    ): Boolean {
        val expiredLocks = lockTable.rows.filterNot { it.isActiveAt(now) }
        if (expiredLocks.isEmpty()) return false
        AppLog.info(LOG_COMPONENT) { "Removing ${expiredLocks.size} expired or invalid remote locks" }
        api.updateCells(
            spreadsheetId = spreadsheetId,
            accessToken = accessToken,
            values = expiredLocks.flatMap { lockTable.clearValuesFor(it.rowNumber) }
        )
        return true
    }

    private fun releaseSafely(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        lockId: String,
        accessToken: String
    ) {
        try {
            releaseLock(spreadsheetId, performanceKey, lockId, accessToken)
            AppLog.debug(LOG_COMPONENT) {
                "Released remote performance lock; tab=$sheetTitle, " +
                    "lockId=${lockId.toAbbreviatedId()}"
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            AppLog.warning(LOG_COMPONENT, exception) {
                "Remote lock release failed and will be left to expire; tab=$sheetTitle, " +
                    "lockId=${lockId.toAbbreviatedId()}"
            }
        }
    }

    private fun releaseLock(
        spreadsheetId: String,
        performanceKey: String,
        lockId: String,
        accessToken: String
    ) {
        val lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.ownedLock(performanceKey, lockId, deviceId) ?: run {
            AppLog.warning(LOG_COMPONENT) {
                "Skipped remote lock release because the owned lock row no longer exists; " +
                    "expected=${lockId.toAbbreviatedId()}"
            }
            return
        }
        api.updateCells(
            spreadsheetId,
            accessToken,
            lockTable.clearValuesFor(existingLock.rowNumber)
        )
    }

    private fun renewLock(
        spreadsheetId: String,
        performanceKey: String,
        sheetTitle: String,
        lockId: String,
        accessToken: String
    ) {
        val lockTable = api.loadValues(spreadsheetId, LOCK_SHEET_TITLE, accessToken).toLockTable()
        val existingLock = lockTable.ownedLock(performanceKey, lockId, deviceId)
        if (existingLock == null) {
            AppLog.warning(LOG_COMPONENT) {
                "Remote lock renewal lost ownership; tab=$sheetTitle, " +
                    "lockId=${lockId.toAbbreviatedId()}"
            }
            throw GoogleSheetLockedException(
                failure = GoogleSheetLockFailure.RENEWAL_LOST,
                sheetTitle = sheetTitle,
                holderDeviceId = lockTable.activeLockFor(performanceKey, Instant.now())?.deviceId
            )
        }
        val now = Instant.now()
        api.updateCells(
            spreadsheetId = spreadsheetId,
            accessToken = accessToken,
            values = lockTable.valuesFor(
                rowNumber = existingLock.rowNumber,
                performanceKey = performanceKey,
                lockId = lockId,
                lockedAt = now.toString(),
                expiresAt = now.plusSeconds(LOCK_DURATION_SECONDS).toString(),
                deviceId = deviceId
            )
        )
        AppLog.verbose(LOG_COMPONENT) {
            "Renewed remote performance lock; tab=$sheetTitle, lockId=${lockId.toAbbreviatedId()}"
        }
    }

    private companion object {
        const val LOG_COMPONENT = "Sheets"
        const val LOCK_DURATION_SECONDS = 30L
        const val LOCK_RENEWAL_INTERVAL_MILLIS = 10_000L
    }
}
