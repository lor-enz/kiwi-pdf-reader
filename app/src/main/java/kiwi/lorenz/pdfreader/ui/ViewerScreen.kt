package kiwi.lorenz.pdfreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.pdf.models.FormEditInfo
import kiwi.lorenz.pdfreader.DocumentState
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SearchState
import kotlinx.coroutines.launch

/** Material 3 small top app bar height. */
private val TopBarHeight = 64.dp

/** What the viewer screen asks for, implemented by the viewer activity. */
interface ViewerActions {
    fun back()
    fun openAnother()
    fun retry()
    fun submitPassword(password: String, remember: Boolean)
    fun unlockFinished()
    fun pageRestored()
    fun pageChanged(page: Int)
    fun formEdited(edit: FormEditInfo)
    fun openSearch()
    fun closeSearch()
    fun changeQuery(query: String)
    fun nextMatch()
    fun previousMatch()
    fun print()
    fun share()
    fun saveCopy()
    fun clearFilledFields()
    fun messageShown()
}

/**
 * [pendingRestorePage] is the page to scroll to once the document shows, [isFilledIn] whether it
 * shows filled-in form fields, and [message] a string resource for the snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentState: DocumentState,
    searchState: SearchState,
    pendingRestorePage: Int?,
    isFilledIn: Boolean,
    message: Int?,
    actions: ViewerActions,
) {
    BackHandler(onBack = if (searchState.isActive) actions::closeSearch else actions::back)

    val loaded = documentState as? DocumentState.Loaded
    val title = when (documentState) {
        is DocumentState.Loaded -> documentState.name
        is DocumentState.NeedsPassword -> documentState.name
        else -> ""
    }

    // The bar slides away while scrolling down through a document and returns when scrolling up.
    var isBarHiddenByScroll by remember(loaded?.document) { mutableStateOf(false) }
    val isBarVisible = searchState.isActive || loaded == null || !isBarHiddenByScroll

    var isToolsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isClearDialogOpen by rememberSaveable { mutableStateOf(false) }
    if (isToolsSheetOpen && loaded != null) {
        ToolsSheet(
            name = loaded.name,
            onDismiss = { isToolsSheetOpen = false },
            onSearch = actions::openSearch,
            onPrint = actions::print,
            onShare = actions::share,
            onSaveCopy = actions::saveCopy,
            isFilledIn = isFilledIn,
            onClearFilled = { isClearDialogOpen = true },
        )
    }

    if (isClearDialogOpen) {
        ClearFilledDialog(
            onConfirm = {
                isClearDialogOpen = false
                actions.clearFilledFields()
            },
            onDismiss = { isClearDialogOpen = false },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkFailedMessage = stringResource(R.string.link_failed)
    val messageText = message?.let { stringResource(it) }
    LaunchedEffect(messageText) {
        messageText ?: return@LaunchedEffect
        // Cleared right away, so the same message can come again; that restarts this effect,
        // so the snackbar is shown in the screen's scope.
        actions.messageShown()
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(messageText)
        }
    }

    // The bar is laid over the content, so hiding it doesn't shift the pages.
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val contentPadding = PaddingValues(
        top = statusBarHeight + TopBarHeight,
        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AnimatedContent(
            targetState = documentState,
            contentKey = { it::class },
            transitionSpec = {
                // The unlocked document fades in as the opened lock fades out; all else switches at once.
                if (initialState is DocumentState.NeedsPassword && targetState is DocumentState.Loaded) {
                    fadeIn(tween(LockMotion.REVEAL_MS)) togetherWith fadeOut(tween(LockMotion.REVEAL_MS))
                } else {
                    EnterTransition.None togetherWith ExitTransition.None
                }
            },
            label = "document",
        ) { state ->
            when (state) {
                is DocumentState.Loaded -> DocumentView(
                    documentState = state,
                    searchState = searchState,
                    pendingRestorePage = pendingRestorePage,
                    onPageRestored = actions::pageRestored,
                    onPageChanged = actions::pageChanged,
                    contentPadding = contentPadding,
                    onBarHiddenChange = { isBarHiddenByScroll = it },
                    onFormEdit = actions::formEdited,
                    onLinkFailed = {
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(linkFailedMessage)
                        }
                    },
                )

                is DocumentState.NeedsPassword -> PasswordPrompt(
                    state = state,
                    onSubmit = actions::submitPassword,
                    onUnlocked = actions::unlockFinished,
                    // The keyboard inset includes the navigation bar, which is already padded.
                    modifier = Modifier
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
                )

                is DocumentState.Failed -> FailureMessage(
                    reason = state.reason,
                    onOpenAnother = actions::openAnother,
                    onRetry = actions::retry,
                    modifier = Modifier.padding(contentPadding),
                )

                DocumentState.Loading, DocumentState.Empty -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(statusBarHeight)
                    .background(MaterialTheme.colorScheme.surface)
            )
            AnimatedVisibility(
                visible = isBarVisible,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                if (searchState.isActive) {
                    SearchBar(
                        searchState = searchState,
                        onClose = actions::closeSearch,
                        onQueryChange = actions::changeQuery,
                        onNext = actions::nextMatch,
                        onPrevious = actions::previousMatch,
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = actions::back, modifier = Modifier.testTag(TestTags.BACK)) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (loaded != null) {
                                IconButton(
                                    onClick = { isToolsSheetOpen = true },
                                    modifier = Modifier.testTag(TestTags.MORE_OPTIONS),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_more_vert),
                                        stringResource(R.string.more_options),
                                    )
                                }
                            }
                        },
                        windowInsets = BarInsets,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

/** The status bar is handled separately; the bar itself only avoids side cutouts. */
internal val BarInsets
    @Composable get() = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal)

/** The document's tools, collected in one place to keep the top bar calm. */
@Composable
private fun ToolsSheet(
    name: String,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onSaveCopy: () -> Unit,
    isFilledIn: Boolean,
    onClearFilled: () -> Unit,
) {
    // Says that the filled-in fields come along, so nobody wonders whether they do.
    val note = R.string.with_filled_in_fields.takeIf { isFilledIn }
    ActionSheet(onDismiss) {
        Heading(name, TestTags.TOOLS_SHEET_TITLE)
        Action(R.drawable.ic_search, R.string.search, TestTags.TOOL_SEARCH, onClick = onSearch)
        Action(R.drawable.ic_print, R.string.print, TestTags.TOOL_PRINT, note, onPrint)
        Action(R.drawable.ic_share, R.string.share, TestTags.TOOL_SHARE, note, onShare)
        Action(R.drawable.ic_save, R.string.save_copy, TestTags.TOOL_SAVE_COPY, note, onSaveCopy)
        if (isFilledIn) {
            Action(R.drawable.ic_restart, R.string.clear_filled_in, TestTags.TOOL_CLEAR_FILLED, onClick = onClearFilled)
        }
    }
}

/** Clearing throws away what was typed into the form, so it's confirmed first. */
@Composable
private fun ClearFilledDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_filled_in_title)) },
        text = { Text(stringResource(R.string.clear_filled_in_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(TestTags.CONFIRM_CLEAR_FILLED)) {
                Text(stringResource(R.string.clear))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        // A dialog is a window of its own, so it doesn't inherit exposed test tags from the root.
        modifier = Modifier.exposeTestTags(),
    )
}
