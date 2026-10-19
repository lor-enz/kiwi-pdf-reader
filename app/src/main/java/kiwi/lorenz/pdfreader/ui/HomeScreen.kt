package kiwi.lorenz.pdfreader.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.PdfDefault
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.info.DocumentInfo
import kiwi.lorenz.pdfreader.info.PdfMetadata
import kiwi.lorenz.pdfreader.ui.theme.PrimaryButton
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(
    recentFiles: List<RecentFile>,
    thumbnailFile: (RecentFile) -> File,
    onOpenPdf: () -> Unit,
    pdfDefault: PdfDefault,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onShareRecent: (RecentFile) -> Unit,
    documentInfo: suspend (RecentFile) -> DocumentInfo,
    onForgetPassword: (RecentFile) -> Unit,
    pendingRemoval: RecentFile?,
    onRemoveRecent: (RecentFile) -> Unit,
    onUndoRemoval: (RecentFile) -> Unit,
    onRemovalDone: (RecentFile) -> Unit,
    message: Int?,
    onMessageShown: () -> Unit,
) {
    // Long-pressing an entry opens its sheet; kept by id, as the entry itself changes.
    var sheetEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var infoEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    recentFiles.find { it.id == sheetEntryId }?.let { entry ->
        RecentFileSheet(
            entry = entry,
            onDismiss = { sheetEntryId = null },
            onShare = { onShareRecent(entry) },
            onInfo = { infoEntryId = entry.id },
            onForgetPassword = { onForgetPassword(entry) },
            onRemove = { onRemoveRecent(entry) },
        )
    }
    recentFiles.find { it.id == infoEntryId }?.let { entry ->
        InfoSheet(
            name = entry.name,
            title = entry.title,
            pageCount = entry.pageCount,
            load = { documentInfo(entry) },
            onDismiss = { infoEntryId = null },
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

    val scope = rememberCoroutineScope()
    val messageText = message?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        messageText ?: return@LaunchedEffect
        // Cleared right away, so the same message can come again; that restarts this effect,
        // so the snackbar is shown in the screen's scope. It waits for an undo snackbar instead
        // of dismissing it, which would end the undo.
        onMessageShown()
        scope.launch { snackbarHostState.showSnackbar(messageText) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // No top bar: the cog sits below the status bar and scrolls away with the list. Its icon
        // is 24 dp from the right edge, like the sheets' content (the button has 12 dp around it).
        val topInset = innerPadding.calculateTopPadding()
        // Removing the last entry switches to the centered layout; fade instead of jumping.
        Crossfade(targetState = recentFiles.isEmpty(), label = "recent files") { isEmpty ->
            if (isEmpty) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Header(onOpenPdf, pdfDefault, onMakeDefault, onChangeDefault)
                    }
                    SettingsButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = topInset, end = 12.dp),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = topInset,
                            bottom = innerPadding.calculateBottomPadding() + 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        item {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                SettingsButton(
                                    onClick = onOpenSettings,
                                    modifier = Modifier
                                        .align(Alignment.End)
                                        // Into the list's 16 dp side padding.
                                        .offset(x = 4.dp),
                                )
                                Spacer(Modifier.height(24.dp))
                                Header(onOpenPdf, pdfDefault, onMakeDefault, onChangeDefault)
                            }
                        }
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
                                // Removed entries fade out and the rest moves up, reordered ones slide.
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    // Keeps the scrolled list from showing behind the status bar's icons.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.background),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag(TestTags.SETTINGS)) {
        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
    }
}

@Composable
private fun Header(
    onOpenPdf: () -> Unit,
    pdfDefault: PdfDefault,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
) {
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
        PrimaryButton(
            onClick = onOpenPdf,
            modifier = Modifier.testTag(TestTags.OPEN_PDF),
        ) {
            Text(stringResource(R.string.open_pdf))
        }
        DefaultHint(pdfDefault, onMakeDefault, onChangeDefault)
    }
}

/** A quiet hint while another app, or none, opens PDFs from other apps, with a way to change it. */
@Composable
private fun DefaultHint(
    pdfDefault: PdfDefault,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
) {
    // Keeps showing the last state while it disappears.
    var shown by remember { mutableStateOf(pdfDefault) }
    if (pdfDefault != PdfDefault.ThisApp) shown = pdfDefault
    AnimatedVisibility(visible = pdfDefault != PdfDefault.ThisApp) {
        val other = shown as? PdfDefault.OtherApp
        HintBox(
            isOtherApp = other != null,
            onClick = { if (other != null) onChangeDefault(other) else onMakeDefault() },
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** Material's banner: icon and text side by side, the action at the end below them. */
@Composable
private fun HintBox(isOtherApp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.End)
                .testTag(if (isOtherApp) TestTags.CLEAR_OTHER_DEFAULT else TestTags.MAKE_DEFAULT),
        ) { Text(stringResource(if (isOtherApp) R.string.clear_other_default else R.string.make_default)) }
    }
}

@Composable
private fun RecentFileRow(
    entry: RecentFile,
    thumbnailFile: (RecentFile) -> File,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
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
        Column(Modifier.weight(1f)) {
            // The title, if the document has one, with the file name smaller below it.
            Text(
                text = entry.title ?: entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.RECENT_TITLE),
            )
            if (entry.title != null) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TestTags.RECENT_FILE_NAME),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = details(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.isProtected) {
            // Open when it opens without asking for the password.
            val isRemembered = entry.encryptedPassword != null
            Spacer(Modifier.width(16.dp))
            Icon(
                painter = painterResource(if (isRemembered) R.drawable.lock_open_left else R.drawable.lock_closed),
                contentDescription = stringResource(if (isRemembered) R.string.password_remembered else R.string.protected_pdf),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(if (isRemembered) TestTags.RECENT_UNLOCKED else TestTags.RECENT_LOCKED),
            )
        }
    }
}

/** What can be done with an entry without opening it. */
@Composable
private fun RecentFileSheet(
    entry: RecentFile,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onForgetPassword: () -> Unit,
    onRemove: () -> Unit,
) {
    ActionSheet(onDismiss) {
        Heading(entry.name, entry.title, TestTags.RECENT_SHEET_TITLE)
        Action(R.drawable.ic_share, R.string.share, TestTags.SHARE_RECENT, onClick = onShare)
        Action(R.drawable.ic_info, R.string.document_info, TestTags.RECENT_INFO, onClick = onInfo)
        if (entry.encryptedPassword != null) {
            Action(R.drawable.ic_key_off, R.string.forget_password, TestTags.FORGET_PASSWORD, onClick = onForgetPassword)
        }
        Action(R.drawable.ic_playlist_remove, R.string.remove_from_list, TestTags.REMOVE_RECENT, onClick = onRemove)
    }
}

@Composable
private fun Thumbnail(entry: RecentFile, thumbnailFile: (RecentFile) -> File) {
    val bitmap by produceState<ImageBitmap?>(null, entry.id, entry.hasThumbnail) {
        // A dropped thumbnail (password forgotten) mustn't stay on screen.
        value = if (entry.hasThumbnail) {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(thumbnailFile(entry).path)?.asImageBitmap()
            }
        } else {
            null
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape),
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
            pdfDefault = PdfDefault.None,
            onMakeDefault = {},
            onChangeDefault = {},
            onOpenSettings = {},
            onOpenRecent = {},
            onShareRecent = {},
            documentInfo = { DocumentInfo(PdfMetadata(), null) },
            onForgetPassword = {},
            pendingRemoval = null,
            onRemoveRecent = {},
            onUndoRemoval = {},
            onRemovalDone = {},
            message = null,
            onMessageShown = {},
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
                RecentFile("1", "", "2024-03 Rechnung.pdf", false, lastOpenedMillis = now, pageCount = 3, title = "Electricity bill March"),
                RecentFile("2", "", "Manual.pdf", true, lastOpenedMillis = now - 86_400_000, pageCount = 28, lastPage = 11),
            ),
            thumbnailFile = { File("") },
            onOpenPdf = {},
            pdfDefault = PdfDefault.OtherApp(""),
            onMakeDefault = {},
            onChangeDefault = {},
            onOpenSettings = {},
            onOpenRecent = {},
            onShareRecent = {},
            documentInfo = { DocumentInfo(PdfMetadata(), null) },
            onForgetPassword = {},
            pendingRemoval = null,
            onRemoveRecent = {},
            onUndoRemoval = {},
            onRemovalDone = {},
            message = null,
            onMessageShown = {},
        )
    }
}
