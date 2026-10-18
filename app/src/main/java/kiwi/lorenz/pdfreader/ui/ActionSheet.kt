package kiwi.lorenz.pdfreader.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/** A bottom sheet with a list of actions; each one closes the sheet before it runs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(onDismiss: () -> Unit, content: @Composable ActionSheetScope.() -> Unit) {
    // The sheet is a window of its own, so it doesn't inherit testTagsAsResourceId from the root.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        ActionSheetScope(this, onDismiss).content()
        Spacer(Modifier.height(8.dp))
    }
}

class ActionSheetScope(column: ColumnScope, private val onDismiss: () -> Unit) : ColumnScope by column {
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
