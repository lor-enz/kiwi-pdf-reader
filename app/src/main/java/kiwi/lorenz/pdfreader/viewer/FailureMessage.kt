package kiwi.lorenz.pdfreader.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.PrimaryButton

/** [onRetry] is offered instead of [onOpenAnother] when the failure is most likely passing. */
@Composable
internal fun FailureMessage(
    failure: DocumentState.Failed,
    onOpenAnother: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reason = failure.reason
    val name = failure.fileName.orEmpty()
    val others = failure.others
    val (title, text) = when (reason) {
        FailureReason.DAMAGED -> R.string.error_damaged_title to stringResource(R.string.error_damaged)
        FailureReason.NOT_PDF -> R.string.error_not_pdf_title to stringResource(R.string.error_not_pdf)
        FailureReason.NO_PERMISSION -> R.string.error_no_permission_title to stringResource(R.string.error_no_permission)
        FailureReason.UNAVAILABLE -> R.string.error_unavailable_title to stringResource(R.string.error_unavailable)
        FailureReason.TEMPORARY -> R.string.error_temporary_title to stringResource(R.string.error_temporary)
        FailureReason.NO_IMAGES -> R.string.error_no_images_title to stringResource(R.string.error_no_images)
        FailureReason.MERGE_NEEDS_PASSWORD -> R.string.error_merge_title to if (others == 0) {
            stringResource(R.string.error_merge_password, name)
        } else {
            pluralStringResource(R.plurals.error_merge_password_others, others, name, others)
        }
        FailureReason.MERGE_UNREADABLE -> R.string.error_merge_title to if (others == 0) {
            stringResource(R.string.error_merge_unreadable, name)
        } else {
            pluralStringResource(R.plurals.error_merge_unreadable_others, others, name, others)
        }
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
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.FAILURE_MESSAGE),
            )
            Spacer(Modifier.height(24.dp))
            if (reason == FailureReason.TEMPORARY) {
                PrimaryButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag(TestTags.TRY_AGAIN),
                ) {
                    Text(stringResource(R.string.try_again))
                }
            } else {
                PrimaryButton(
                    onClick = onOpenAnother,
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
        failure = DocumentState.Failed(FailureReason.TEMPORARY),
        onOpenAnother = {},
        onRetry = {},
    )
}
