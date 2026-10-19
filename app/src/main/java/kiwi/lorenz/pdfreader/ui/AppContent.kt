package kiwi.lorenz.pdfreader.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import kiwi.lorenz.pdfreader.ui.theme.PdfPenguinTheme

/**
 * An activity's content in the app's theme, with test tags exposed (see [exposeTestTags]). Text
 * outside a Surface or Scaffold gets the color for the background, not black, also when dark.
 */
fun ComponentActivity.setAppContent(content: @Composable () -> Unit) = setContent {
    PdfPenguinTheme {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.exposeTestTags()) { content() }
        }
    }
}
