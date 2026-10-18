package kiwi.lorenz.pdfreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.FailureReason
import kiwi.lorenz.pdfreader.R

@Composable
internal fun FailureMessage(
    reason: FailureReason,
    onOpenAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    FailureReason.DAMAGED -> R.string.error_damaged
                    FailureReason.NOT_PDF -> R.string.error_not_pdf
                    FailureReason.NO_PERMISSION -> R.string.error_no_permission
                    FailureReason.UNAVAILABLE -> R.string.error_unavailable
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(TestTags.FAILURE_MESSAGE),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenAnother,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(TestTags.OPEN_ANOTHER),
        ) {
            Text(stringResource(R.string.open_another))
        }
    }
}
