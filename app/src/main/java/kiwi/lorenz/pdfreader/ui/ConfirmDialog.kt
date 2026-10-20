package kiwi.lorenz.pdfreader.ui

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kiwi.lorenz.pdfreader.R

/** Confirms an action that can't be undone, like deleting what the app stores. */
@Composable
fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes text: Int,
    @StringRes confirm: Int,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        // A dialog is a window of its own, so it doesn't inherit exposed test tags from the root.
        modifier = Modifier.exposeTestTags(),
    )
}
