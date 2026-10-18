package kiwi.lorenz.pdfreader.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme

/** An activity's content in the app's theme, with test tags exposed (see [exposeTestTags]). */
fun ComponentActivity.setAppContent(content: @Composable () -> Unit) = setContent {
    KiwiPDFReaderTheme {
        Box(Modifier.exposeTestTags()) { content() }
    }
}
