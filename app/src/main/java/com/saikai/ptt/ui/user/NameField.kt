package com.saikai.ptt.ui.user

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.saikai.ptt.R
import com.saikai.ptt.core.domain.UserNameValidator

/**
 * The one place a name is typed.
 *
 * Shared by the welcome screen and the editor so that both enforce the same
 * limits and say the same thing when they are exceeded. The two limits are not
 * obvious -- twenty-four characters *and* sixty-four UTF-8 bytes
 * (`docs/03_Protocol.md` section 5.1) -- and a second implementation of them
 * would drift.
 *
 * The counter is always visible rather than appearing once the limit is passed:
 * a user typing a Burmese or Bengali name can reach the byte limit at eleven
 * characters, and a field that says nothing until it refuses is a field that
 * refuses without warning.
 */
@Composable
fun NameField(
    value: String,
    onValueChange: (String) -> Unit,
    problem: NameProblem?,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {},
) {
    val codePoints = value.codePointCount(0, value.length)

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        isError = problem != null,
        label = { Text(stringResource(R.string.user_name_label)) },
        supportingText = {
            Text(
                text = when (problem) {
                    NameProblem.TOO_LONG -> stringResource(
                        R.string.user_name_too_long,
                        UserNameValidator.MAX_CODE_POINTS,
                    )

                    NameProblem.TOO_LONG_IN_BYTES -> stringResource(
                        R.string.user_name_too_long_in_bytes,
                    )

                    null -> stringResource(
                        R.string.user_name_counter,
                        codePoints,
                        UserNameValidator.MAX_CODE_POINTS,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        modifier = modifier.fillMaxWidth(),
    )
}
