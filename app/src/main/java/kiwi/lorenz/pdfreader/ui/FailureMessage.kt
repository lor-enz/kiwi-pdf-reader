package kiwi.lorenz.pdfreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.FailureReason
import kiwi.lorenz.pdfreader.R

/** [onRetry] is offered instead of [onOpenAnother] when the failure is most likely passing. */
@Composable
internal fun FailureMessage(
    reason: FailureReason,
    onOpenAnother: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, text) = when (reason) {
        FailureReason.DAMAGED -> R.string.error_damaged_title to R.string.error_damaged
        FailureReason.NOT_PDF -> R.string.error_not_pdf_title to R.string.error_not_pdf
        FailureReason.NO_PERMISSION -> R.string.error_no_permission_title to R.string.error_no_permission
        FailureReason.UNAVAILABLE -> R.string.error_unavailable_title to R.string.error_unavailable
        FailureReason.TEMPORARY -> R.string.error_temporary_title to R.string.error_temporary
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            // Keeps lines readable on tablets.
            modifier = Modifier.widthIn(max = 400.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Decorative: the title already says what happened. Grey, so it doesn't alarm.
            Icon(
                painter = painterResource(R.drawable.ic_error),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.FAILURE_TITLE),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.FAILURE_MESSAGE),
            )
            Spacer(Modifier.height(24.dp))
            if (reason == FailureReason.TEMPORARY) {
                Button(
                    onClick = onRetry,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(TestTags.TRY_AGAIN),
                ) {
                    Text(stringResource(R.string.try_again))
                }
            } else {
                Button(
                    onClick = onOpenAnother,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.testTag(TestTags.OPEN_ANOTHER),
                ) {
                    Text(stringResource(R.string.open_another))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FailureMessagePreview() {
    FailureMessage(
        reason = FailureReason.TEMPORARY,
        onOpenAnother = {},
        onRetry = {},
    )
}
