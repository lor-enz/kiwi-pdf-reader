package kiwi.lorenz.pdfreader.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(
    recentFiles: List<RecentFile>,
    thumbnailFile: (RecentFile) -> File,
    onOpenPdf: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    pendingRemoval: RecentFile?,
    onRemoveRecent: (RecentFile) -> Unit,
    onUndoRemoval: (RecentFile) -> Unit,
    onRemovalDone: (RecentFile) -> Unit,
) {
    // Long-pressing an entry opens its sheet; kept by id, as the entry itself changes.
    var sheetEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    recentFiles.find { it.id == sheetEntryId }?.let { entry ->
        RecentFileSheet(
            entry = entry,
            onDismiss = { sheetEntryId = null },
            onRemove = { onRemoveRecent(entry) },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val removedMessage = stringResource(R.string.removed_from_list)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(pendingRemoval) {
        val entry = pendingRemoval ?: return@LaunchedEffect
        var isUndone = false
        try {
            // With an action, the default duration would be indefinite.
            val result = snackbarHostState.showSnackbar(removedMessage, undoLabel, duration = SnackbarDuration.Long)
            isUndone = result == SnackbarResult.ActionPerformed
        } finally {
            // Also when leaving the screen: the files are only deleted once undo is impossible.
            if (isUndone) onUndoRemoval(entry) else onRemovalDone(entry)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        if (recentFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(onOpenPdf)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 32.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Header(onOpenPdf) }
                item {
                    Text(
                        text = stringResource(R.string.recent_files).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 4.dp, start = 4.dp),
                    )
                }
                items(recentFiles, key = { it.id }) { entry ->
                    RecentFileRow(
                        entry = entry,
                        thumbnailFile = thumbnailFile,
                        onClick = { onOpenRecent(entry) },
                        onLongClick = { sheetEntryId = entry.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onOpenPdf: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onOpenPdf,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(TestTags.OPEN_PDF),
        ) {
            Text(stringResource(R.string.open_pdf))
        }
    }
}

@Composable
private fun RecentFileRow(
    entry: RecentFile,
    thumbnailFile: (RecentFile) -> File,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.more_options),
            )
            .testTag(TestTags.RECENT_FILE)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry, thumbnailFile)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = details(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** What can be done with an entry without opening it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentFileSheet(
    entry: RecentFile,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    // The sheet is a window of its own, so it doesn't inherit testTagsAsResourceId from the root.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
                .testTag(TestTags.RECENT_SHEET_TITLE),
        )

        @Composable
        fun Action(icon: Int, label: Int, tag: String, action: () -> Unit) = ListItem(
            headlineContent = { Text(stringResource(label)) },
            leadingContent = { Icon(painterResource(icon), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable {
                    onDismiss()
                    action()
                }
                .testTag(tag),
        )
        Action(R.drawable.ic_playlist_remove, R.string.remove_from_list, TestTags.REMOVE_RECENT, onRemove)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Thumbnail(entry: RecentFile, thumbnailFile: (RecentFile) -> File) {
    val bitmap by produceState<ImageBitmap?>(null, entry.id, entry.hasThumbnail) {
        if (entry.hasThumbnail) {
            value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(thumbnailFile(entry).path)?.asImageBitmap()
            }
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "PDF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun details(entry: RecentFile): String {
    val now = System.currentTimeMillis()
    val opened = if (now - entry.lastOpenedMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            entry.lastOpenedMillis, now, DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    if (entry.pageCount == 0) return opened
    val page = stringResource(R.string.page_of, entry.lastPage + 1, entry.pageCount)
    return stringResource(R.string.recent_details, page, opened)
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    KiwiPDFReaderTheme {
        HomeScreen(
            recentFiles = emptyList(),
            thumbnailFile = { File("") },
            onOpenPdf = {},
            onOpenRecent = {},
            pendingRemoval = null,
            onRemoveRecent = {},
            onUndoRemoval = {},
            onRemovalDone = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenRecentPreview() {
    val now = System.currentTimeMillis()
    KiwiPDFReaderTheme {
        HomeScreen(
            recentFiles = listOf(
                RecentFile("1", "", "Electricity bill March.pdf", false, lastOpenedMillis = now, pageCount = 3),
                RecentFile("2", "", "Manual.pdf", true, lastOpenedMillis = now - 86_400_000, pageCount = 28, lastPage = 11),
            ),
            thumbnailFile = { File("") },
            onOpenPdf = {},
            onOpenRecent = {},
            pendingRemoval = null,
            onRemoveRecent = {},
            onUndoRemoval = {},
            onRemovalDone = {},
        )
    }
}
