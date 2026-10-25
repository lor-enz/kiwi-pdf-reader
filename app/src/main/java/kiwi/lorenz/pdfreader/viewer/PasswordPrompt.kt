package kiwi.lorenz.pdfreader.viewer

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.PrimaryButton

@Composable
internal fun PasswordPrompt(
    state: DocumentState.NeedsPassword,
    onSubmit: (password: String, remember: Boolean) -> Unit,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var isPasswordShown by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val isUnlocking = state.unlocked != null
    val canSubmit = password.isNotEmpty() && !state.isChecking && !isUnlocking
    // In landscape, stacked, the keyboard leaves room for the field only: the lock and the button
    // would be out of sight. Side by side, without the keyboard all fits.
    val window = LocalWindowInfo.current.containerSize
    val isSideBySide = window.width > window.height && with(LocalDensity.current) { window.width.toDp() >= 600.dp }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (canSubmit) {
            // Side by side, the keyboard would cover the lock opening or shaking, and the message
            // below the field. Tapping the field brings it back for another try.
            if (isSideBySide) keyboard?.hide()
            onSubmit(password, rememberPassword)
        }
    }

    val lock = rememberLockState()
    LaunchedEffect(isUnlocking) {
        if (isUnlocking) {
            lock.unlock()
            onUnlocked()
        }
    }
    // Shakes for each wrong password, not again when the screen is recreated.
    val view = LocalView.current
    var shakenFor by rememberSaveable { mutableIntStateOf(state.wrongAttempts) }
    LaunchedEffect(state.wrongAttempts) {
        if (state.wrongAttempts > shakenFor) {
            shakenFor = state.wrongAttempts
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            lock.shake()
        }
    }

    val intro = @Composable {
        Lock(lock, Modifier.testTag(TestTags.PASSWORD_LOCK))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Decorative: the name says which document it is.
            Icon(
                painter = painterResource(R.drawable.pdf_box),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = state.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .testTag(TestTags.PASSWORD_FILE_NAME),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.password_title),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
    val form = @Composable {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            isError = state.isWrongPassword && !isUnlocking,
            // While the lock opens, the message's line stays empty, so the layout doesn't jump.
            supportingText = if (state.isWrongPassword) {
                { Text(if (isUnlocking) "" else stringResource(R.string.wrong_password)) }
            } else {
                null
            },
            visualTransformation = if (isPasswordShown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { isPasswordShown = !isPasswordShown }) {
                    Icon(
                        painterResource(if (isPasswordShown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (isPasswordShown) R.string.hide_password else R.string.show_password),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(TestTags.PASSWORD_FIELD),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = rememberPassword,
                    onValueChange = { rememberPassword = it },
                    role = Role.Checkbox,
                )
                .testTag(TestTags.REMEMBER_PASSWORD)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = rememberPassword, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.remember_password), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            onClick = submit,
            // Stays as it is while the lock opens, instead of flashing disabled.
            enabled = canSubmit || isUnlocking,
            modifier = Modifier.testTag(TestTags.SUBMIT_PASSWORD),
        ) {
            if (state.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Text(stringResource(R.string.open))
            }
        }
    }

    if (isSideBySide) {
        Row(
            modifier = modifier
                .fillMaxSize()
                // In landscape, cutouts and the navigation bar's buttons are at the sides.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Not moved by the keyboard, so the lock doesn't jump when it closes.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                intro()
            }
            Spacer(Modifier.width(32.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                form()
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            intro()
            Spacer(Modifier.height(24.dp))
            form()
        }
    }
}
