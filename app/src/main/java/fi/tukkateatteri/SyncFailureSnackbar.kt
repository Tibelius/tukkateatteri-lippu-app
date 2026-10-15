package fi.tukkateatteri

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import fi.tukkateatteri.data.Performance

@Composable
internal fun SyncFailureSnackbarEffect(
    failure: SheetSyncFailure?,
    performance: Performance?,
    hostState: SnackbarHostState,
    onDismiss: (Long) -> Unit,
    onRetry: (() -> Unit)?
) {
    val message = if (failure != null && performance != null) {
        stringResource(failure.reason.messageResId, performance.displayName)
    } else {
        null
    }
    val retryLabel = stringResource(R.string.retry_upload)
    val currentRetry by rememberUpdatedState(onRetry)
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(failure?.id, message) {
        if (failure == null || message == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = retryLabel.takeIf { currentRetry != null },
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        currentDismiss(failure.id)
        if (result == SnackbarResult.ActionPerformed) currentRetry?.invoke()
    }
}
