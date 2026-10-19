package kiwi.lorenz.pdfreader.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    ) {
        ActionSheetScope(this, onDismiss).content()
        Spacer(Modifier.height(8.dp))
    }
}

class ActionSheetScope(column: ColumnScope, private val onDismiss: () -> Unit) : ColumnScope by column {
    /** The document the actions are for. Icon and name line up with the actions' icons and labels. */
    @Composable
    fun Heading(name: String, tag: String) = Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 24.dp, bottom = 8.dp),
    ) {
        // Decorative: the name says which document it is.
        Icon(
            painter = painterResource(R.drawable.pdf_box),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(tag),
        )
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
            .testTag(tag),
    )
}
