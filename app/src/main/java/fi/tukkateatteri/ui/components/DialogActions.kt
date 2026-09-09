package fi.tukkateatteri.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fi.tukkateatteri.R

@Composable
fun RowScope.CancelSaveActions(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true
) {
    Spacer(modifier = Modifier.weight(1f))
    TextButton(onClick = onCancel) {
        Text(stringResource(R.string.cancel))
    }
    Button(
        onClick = onSave,
        enabled = saveEnabled
    ) {
        Text(stringResource(R.string.save))
    }
}
