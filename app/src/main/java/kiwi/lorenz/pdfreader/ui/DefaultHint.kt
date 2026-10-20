package kiwi.lorenz.pdfreader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.PdfDefault
import kiwi.lorenz.pdfreader.R

/**
 * A quiet hint while another app, or none, opens PDFs from other apps, with a way to change it.
 * [onNotNow] adds a button that hides it for a while (Home); without it, it can't be dismissed.
 */
@Composable
fun DefaultHint(
    pdfDefault: PdfDefault,
    isVisible: Boolean,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
    modifier: Modifier = Modifier,
    onNotNow: (() -> Unit)? = null,
) {
    // Keeps showing the last state while it disappears.
    var shown by remember { mutableStateOf(pdfDefault) }
    if (pdfDefault != PdfDefault.ThisApp) shown = pdfDefault
    AnimatedVisibility(visible = isVisible) {
        val other = shown as? PdfDefault.OtherApp
        HintBox(
            isOtherApp = other != null,
            onClick = { if (other != null) onChangeDefault(other) else onMakeDefault() },
            onNotNow = onNotNow,
            // Inside, so the spacing collapses along with the hint.
            modifier = modifier,
        )
    }
}

/** Material's banner: icon and text side by side, the actions at the end below them. */
@Composable
private fun HintBox(isOtherApp: Boolean, onClick: () -> Unit, onNotNow: (() -> Unit)?, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(TestTags.DEFAULT_HINT)
            .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp),
    ) {
        Row(Modifier.padding(end = 8.dp)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text(
                text = stringResource(if (isOtherApp) R.string.other_default_pdf_app else R.string.not_default_pdf_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (onNotNow != null) {
                TextButton(onClick = onNotNow, modifier = Modifier.testTag(TestTags.NOT_NOW)) {
                    Text(stringResource(R.string.not_now))
                }
            }
            TextButton(
                onClick = onClick,
                modifier = Modifier.testTag(if (isOtherApp) TestTags.CLEAR_OTHER_DEFAULT else TestTags.MAKE_DEFAULT),
            ) { Text(stringResource(if (isOtherApp) R.string.clear_other_default else R.string.make_default)) }
        }
    }
}
