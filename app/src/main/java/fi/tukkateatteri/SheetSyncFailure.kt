package fi.tukkateatteri

import androidx.annotation.StringRes
import fi.tukkateatteri.data.GoogleSheetChangePendingException
import fi.tukkateatteri.data.GoogleSheetSourceChangedException
import fi.tukkateatteri.data.GoogleSheetSynchronizationConflictException
import fi.tukkateatteri.data.spreadsheet.GoogleSheetLockedException

internal data class SheetSyncFailure(
    val id: Long,
    val performanceId: Long,
    val reason: SheetSyncFailureReason
)

internal enum class SheetSyncFailureReason(@param:StringRes val messageResId: Int) {
    UPLOAD_FAILED(R.string.sheet_upload_failed),
    LOCKED(R.string.sheet_upload_locked),
    CONFLICT(R.string.sheet_upload_conflict),
    SOURCE_CHANGED(R.string.sheet_upload_source_changed),
    AUTHORIZATION_REQUIRED(R.string.sheet_upload_authorization_required)
}

internal fun Throwable.toSheetSyncFailureReason(): SheetSyncFailureReason {
    val failure = if (this is GoogleSheetChangePendingException) cause ?: this else this
    return when (failure) {
        is GoogleSheetLockedException -> SheetSyncFailureReason.LOCKED
        is GoogleSheetSynchronizationConflictException -> SheetSyncFailureReason.CONFLICT
        is GoogleSheetSourceChangedException -> SheetSyncFailureReason.SOURCE_CHANGED
        else -> SheetSyncFailureReason.UPLOAD_FAILED
    }
}
