package kiwi.lorenz.pdfreader.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R

/** A bottom sheet with a list of actions; each one closes the sheet before it runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, content: @Composable ActionSheetScope.() -> Unit) {
    // Fully open right away: the half-open state is what it would return to after recreation
    // (e.g. switching to dark mode), even after being dragged up.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The sheet is a window of its own, so it doesn't inherit exposed test tags from the root.
        modifier = Modifier.exposeTestTags(),
        // The navigation bar's inset goes inside the scrolling content (below), or it would be a
        // strip that hides the actions scrolling past it.
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top) },
    ) {
        // The sheet doesn't scroll by itself: whatever doesn't fit (e.g. in landscape) would be cut
        // off. Dragging the sheet down to close it still works once the content is at its top.
        Column(
            Modifier
                // Scrolls from anywhere on the sheet, also beside content narrower than it.
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            ActionSheetScope(this, onDismiss).content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

class ActionSheetScope(column: ColumnScope, private val onDismiss: () -> Unit) : ColumnScope by column {
    /**
     * The document the actions are for: its [title], if it has one, above its file [name]. Icon
     * and name line up with the actions' icons and labels.
     */
    @Composable
    fun Heading(name: String, title: String?, tag: String) = Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    ) {
        // Decorative: the name says which document it is.
        Icon(
            painter = painterResource(R.drawable.pdf_box),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = title ?: name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(tag),
            )
            if (title != null) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TestTags.HEADING_FILE_NAME),
                )
            }
        }
    }

    /** [note] is shown below the [label], e.g. what the action will include. */
    @Composable
    fun Action(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        tag: String,
        @StringRes note: Int? = null,
        onClick: () -> Unit,
    ) = ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = note?.let { { Text(stringResource(it)) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable {
                onDismiss()
                onClick()
            }
            .testTag(tag)
            // 24 dp from the edges with the list item's own 16 dp; the ripple still spans the sheet.
            .padding(horizontal = 8.dp),
    )
}
