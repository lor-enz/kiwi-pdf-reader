package kiwi.lorenz.pdfreader.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kiwi.lorenz.pdfreader.SnackbarMessage
import kotlinx.coroutines.launch

/**
 * Shows [message] (a string resource, see [SnackbarMessage]) in [hostState]'s snackbar.
 * [replacesCurrent] dismisses a snackbar that's still showing; otherwise the message waits for it,
 * e.g. for an undo snackbar, which dismissing would end.
 */
@Composable
fun MessageEffect(
    message: Int?,
    onShown: () -> Unit,
    hostState: SnackbarHostState,
    replacesCurrent: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val text = message?.let { stringResource(it) }
    LaunchedEffect(text) {
        text ?: return@LaunchedEffect
        // Cleared right away, so the same message can come again; that restarts this effect,
        // so the snackbar is shown in the screen's scope.
        onShown()
        scope.launch {
            if (replacesCurrent) hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(text)
        }
    }
}
