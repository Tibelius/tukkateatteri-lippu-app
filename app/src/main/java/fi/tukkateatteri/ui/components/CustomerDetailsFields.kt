package fi.tukkateatteri.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fi.tukkateatteri.R

@Composable
fun CustomerDetailsFields(
    lastName: String,
    firstName: String,
    contact: String,
    onLastNameChange: (String) -> Unit,
    onFirstNameChange: (String) -> Unit,
    onContactChange: (String) -> Unit,
    fieldsAreOptional: Boolean = false
) {
    val focusManager = LocalFocusManager.current
    val nextFieldAction = KeyboardActions(
        onNext = { focusManager.moveFocus(FocusDirection.Next) }
    )
    val nameKeyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Next
    )
    val contactKeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)

    Column(verticalArrangement = Arrangement.spacedBy(FIELD_SPACING)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NAME_FIELD_SPACING)) {
            OutlinedTextField(
                value = lastName,
                onValueChange = onLastNameChange,
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        stringResource(
                            if (fieldsAreOptional) R.string.last_name_optional else R.string.last_name
                        )
                    )
                },
                keyboardOptions = nameKeyboardOptions,
                keyboardActions = nextFieldAction,
                singleLine = true
            )
            OutlinedTextField(
                value = firstName,
                onValueChange = onFirstNameChange,
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        stringResource(
                            if (fieldsAreOptional) R.string.first_name_optional else R.string.first_name
                        )
                    )
                },
                keyboardOptions = nameKeyboardOptions,
                keyboardActions = nextFieldAction,
                singleLine = true
            )
        }
        OutlinedTextField(
            value = contact,
            onValueChange = onContactChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(
                        if (fieldsAreOptional) {
                            R.string.contact_information_optional
                        } else {
                            R.string.contact_information
                        }
                    )
                )
            },
            keyboardOptions = contactKeyboardOptions,
            keyboardActions = nextFieldAction,
            singleLine = true
        )
    }
}

private val FIELD_SPACING = 12.dp
private val NAME_FIELD_SPACING = 10.dp
