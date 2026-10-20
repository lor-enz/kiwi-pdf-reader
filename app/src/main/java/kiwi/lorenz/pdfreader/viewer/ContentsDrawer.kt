package kiwi.lorenz.pdfreader.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.pdf.PdfPoint
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.info.OutlineItem
import kiwi.lorenz.pdfreader.ui.TestTags

/** How far each level of the outline is indented. */
private val LevelIndent = 16.dp

/**
 * The document's table of contents in a drawer from the left. It opens only by its button, as
 * swiping in from the edge is the system's back gesture and dragging moves the pages; once open,
 * it closes like any drawer. The section being read is marked, and shown when the drawer opens.
 */
@Composable
internal fun ContentsDrawer(
    outline: List<OutlineItem>,
    readingPosition: () -> PdfPoint?,
    drawerState: DrawerState,
    onItemClick: (OutlineItem) -> Unit,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerState, Modifier.testTag(TestTags.CONTENTS_DRAWER)) {
                Contents(outline, readingPosition, isOpen = drawerState.isOpen, onItemClick)
            }
        },
        content = content,
    )
}

/** An item and where it sits: [path] holds its index at each level, from the top. */
private class ContentsRow(val item: OutlineItem, val path: List<Int>) {
    val key = path.joinToString(".")
    val depth get() = path.size - 1
}

@Composable
private fun Contents(
    outline: List<OutlineItem>,
    readingPosition: () -> PdfPoint?,
    isOpen: Boolean,
    onItemClick: (OutlineItem) -> Unit,
) {
    // Branches open as the author left them.
    var expanded by rememberSaveable(outline) {
        mutableStateOf(allRows(outline).filter { it.item.isOpen }.map { it.key }.toSet())
    }
    var current by remember { mutableStateOf<ContentsRow?>(null) }
    val rows = visibleRows(outline, expanded)
    val listState = rememberLazyListState()

    LaunchedEffect(isOpen) {
        if (!isOpen) return@LaunchedEffect
        val current = readingPosition()?.let { currentRow(outline, it) }
            .also { current = it } ?: return@LaunchedEffect
        // The section being read shows, even inside closed branches.
        val ancestors = current.path.indices.drop(1).map { current.path.take(it).joinToString(".") }
        expanded = expanded + ancestors
        val index = visibleRows(outline, expanded).indexOfFirst { it.key == current.key }
        if (index >= 0) listState.scrollToItem(maxOf(0, index - 2))
    }

    LazyColumn(state = listState) {
        item {
            Text(
                text = stringResource(R.string.contents),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
            val isExpanded = row.key in expanded
            fun toggle() {
                expanded = if (isExpanded) expanded - row.key else expanded + row.key
            }
            NavigationDrawerItem(
                label = {
                    Text(row.item.title, maxLines = 3, overflow = TextOverflow.Ellipsis)
                },
                selected = row.key == current?.key,
                // An item that leads nowhere only groups the ones below it.
                onClick = { if (row.item.page != null) onItemClick(row.item) else toggle() },
                icon = {
                    if (row.item.children.isNotEmpty()) {
                        ExpandButton(isExpanded, ::toggle)
                    } else {
                        Spacer(Modifier.size(ExpandButtonSize))
                    }
                },
                badge = row.item.page?.let { { Text((it + 1).toString()) } },
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .padding(start = LevelIndent * row.depth)
                    .animateItem()
                    .testTag(TestTags.CONTENTS_ITEM),
            )
        }
    }
}

private val ExpandButtonSize = 24.dp

@Composable
private fun ExpandButton(isExpanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (isExpanded) 0f else -90f, label = "expand")
    // As small as the icon slot, so items line up with and without it; the item's height makes
    // up for the touch target.
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(ExpandButtonSize)
            .testTag(TestTags.CONTENTS_EXPAND),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = stringResource(if (isExpanded) R.string.collapse else R.string.expand),
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** Every item, in order. */
private fun allRows(outline: List<OutlineItem>, path: List<Int> = emptyList()): List<ContentsRow> =
    outline.flatMapIndexed { index, item ->
        val row = ContentsRow(item, path + index)
        listOf(row) + allRows(item.children, row.path)
    }

private fun visibleRows(outline: List<OutlineItem>, expanded: Set<String>, path: List<Int> = emptyList()): List<ContentsRow> =
    outline.flatMapIndexed { index, item ->
        val row = ContentsRow(item, path + index)
        listOf(row) + if (row.key in expanded) visibleRows(item.children, expanded, row.path) else emptyList()
    }

/** The section [position] is in: the one that starts last before it. */
private fun currentRow(outline: List<OutlineItem>, position: PdfPoint): ContentsRow? {
    val starts = compareBy<OutlineItem>({ it.page }, { it.top ?: 0f })
    val reading = OutlineItem("", position.pageNum, position.y, false, emptyList())
    val started = allRows(outline).filter { it.item.page != null && starts.compare(it.item, reading) <= 0 }
    val start = started.maxOfWithOrNull(starts) { it.item } ?: return null
    // Of items that start at the same place, the innermost.
    return started.last { starts.compare(it.item, start) == 0 }
}
