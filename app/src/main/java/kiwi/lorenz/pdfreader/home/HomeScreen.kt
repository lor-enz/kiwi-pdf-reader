package kiwi.lorenz.pdfreader.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.unit.sp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.defaultapp.DefaultHint
import kiwi.lorenz.pdfreader.defaultapp.PdfDefault
import kiwi.lorenz.pdfreader.info.InfoSheet
import kiwi.lorenz.pdfreader.pdf.DocumentInfo
import kiwi.lorenz.pdfreader.pdf.PdfMetadata
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.ui.ActionSheet
import kiwi.lorenz.pdfreader.ui.ContentMaxWidth
import kiwi.lorenz.pdfreader.ui.MessageEffect
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.PenguinBlue
import kiwi.lorenz.pdfreader.ui.theme.PdfPenguinTheme
import kiwi.lorenz.pdfreader.ui.theme.PrimaryButton
import kiwi.lorenz.pdfreader.util.isSystemUpdateMissing
import kotlinx.coroutines.Dispatchers
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
    isHintPaused: Boolean,
    onPauseHint: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onShareRecent: (RecentFile) -> Unit,
    onOpenRecentWith: (RecentFile) -> Unit,
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
            onOpenWith = { onOpenRecentWith(entry) },
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

    // Waits for an undo snackbar instead of dismissing it, which would end the undo.
    MessageEffect(message, onMessageShown, snackbarHostState)

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
                        Header(onOpenPdf, pdfDefault, onMakeDefault, onChangeDefault, isHintPaused, onPauseHint)
                    }
                    SettingsButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = topInset, end = 12.dp),
                    )
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Side by side when wider than tall: stacked, the header would fill a phone's
                    // screen in landscape and the list only start below it.
                    val isSideBySide = maxWidth > maxHeight && maxWidth >= 600.dp
                    val bottomInset = innerPadding.calculateBottomPadding()
                    val headerMinHeight = (maxHeight - topInset - bottomInset - 48.dp).coerceAtLeast(0.dp)
                    Row(
                        Modifier
                            .fillMaxSize()
                            // In landscape, cutouts and the navigation bar's buttons are at the sides.
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    ) {
                        if (isSideBySide) {
                            // The cog sits in the header's top left corner: beside "Recent" it looked
                            // like an option of the list, and it scrolled away with it.
                            Column(
                                modifier = Modifier
                                    .weight(2f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = topInset, bottom = bottomInset),
                            ) {
                                SettingsButton(onClick = onOpenSettings, modifier = Modifier.padding(start = 12.dp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = headerMinHeight)
                                        .padding(24.dp),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Header(onOpenPdf, pdfDefault, onMakeDefault, onChangeDefault, isHintPaused, onPauseHint)
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(3f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = topInset,
                                bottom = bottomInset + 16.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (isSideBySide) {
                                item {
                                    // As tall as the cog, so "Recent" lines up with it.
                                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                                        RecentLabel(Modifier.padding(start = 4.dp))
                                    }
                                }
                            } else {
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
                                        Header(onOpenPdf, pdfDefault, onMakeDefault, onChangeDefault, isHintPaused, onPauseHint)
                                    }
                                }
                                item {
                                    RecentLabel(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp, start = 4.dp))
                                }
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
private fun RecentLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.recent_files).uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

@Composable
private fun SettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.testTag(TestTags.SETTINGS)) {
        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
    }
}

/**
 * The launcher icon's penguin on its blue, as a circle. On the light background the white page
 * would vanish without the blue. Decorative: the app's name follows.
 */
@Composable
private fun Logo() {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier
            .testTag(TestTags.HOME_LOGO)
            .size(72.dp)
            .clip(CircleShape)
            .background(PenguinBlue)
            // The launcher's foreground shrinks the penguin to 70% to fit the icon masks.
            .scale(1 / 0.7f),
    )
}

@Composable
private fun Header(
    onOpenPdf: () -> Unit,
    pdfDefault: PdfDefault,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
    isHintPaused: Boolean,
    onPauseHint: () -> Unit,
) {
    Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Logo()
        Spacer(Modifier.height(16.dp))
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
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.open_pdf))
        }
        if (isSystemUpdateMissing) SystemUpdateWarning(Modifier.padding(top = 24.dp))
        DefaultHint(
            pdfDefault = pdfDefault,
            isVisible = pdfDefault != PdfDefault.ThisApp && !isHintPaused,
            onMakeDefault = onMakeDefault,
            onChangeDefault = onChangeDefault,
            onNotNow = onPauseHint,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** Shown every time, without a way to dismiss it: much of the app doesn't work without the update. */
@Composable
private fun SystemUpdateWarning(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag(TestTags.SYSTEM_UPDATE_WARNING)
            .padding(16.dp),
    ) {
        Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.system_update_missing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // The title, if the document has one, with the file name smaller below it. A title's
            // lines sit closer together than the file name, so the two lines read as one.
            Text(
                text = entry.title ?: entry.name,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(TestTags.RECENT_TITLE),
            )
            if (entry.title != null) {
                Spacer(Modifier.height(4.dp))
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
    onOpenWith: () -> Unit,
    onInfo: () -> Unit,
    onForgetPassword: () -> Unit,
    onRemove: () -> Unit,
) {
    ActionSheet(onDismiss) {
        Heading(entry.name, entry.title, TestTags.RECENT_SHEET_TITLE)
        Action(R.drawable.ic_share, R.string.share, TestTags.SHARE_RECENT, onClick = onShare)
        Action(R.drawable.ic_open_in_new, R.string.open_with, TestTags.OPEN_WITH_RECENT, onClick = onOpenWith)
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
    PdfPenguinTheme {
        HomeScreen(
            recentFiles = emptyList(),
            thumbnailFile = { File("") },
            onOpenPdf = {},
            pdfDefault = PdfDefault.None,
            onMakeDefault = {},
            onChangeDefault = {},
            isHintPaused = false,
            onPauseHint = {},
            onOpenSettings = {},
            onOpenRecent = {},
            onShareRecent = {},
            onOpenRecentWith = {},
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
    PdfPenguinTheme {
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
            isHintPaused = false,
            onPauseHint = {},
            onOpenSettings = {},
            onOpenRecent = {},
            onShareRecent = {},
            onOpenRecentWith = {},
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
