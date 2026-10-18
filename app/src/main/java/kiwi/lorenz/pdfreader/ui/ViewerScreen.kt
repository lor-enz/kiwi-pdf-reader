package kiwi.lorenz.pdfreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.Highlight
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.compose.rememberPdfViewerState
import kiwi.lorenz.pdfreader.DocumentState
import kiwi.lorenz.pdfreader.FailureReason
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SearchState
import kiwi.lorenz.pdfreader.openLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

// PdfView draws highlights with a MULTIPLY blend, so these must be opaque and light:
// pale tints of the brand green / brown that keep the text underneath readable.
private const val MATCH_COLOR = 0xFFDCE3A6.toInt()
private const val CURRENT_MATCH_COLOR = 0xFFF2B48C.toInt()

private const val PAGE_SAVE_DELAY_MS = 500L

/** Material 3 small top app bar height. */
private val TopBarHeight = 64.dp

/** How far the user has to scroll in one direction before the top bar hides or shows. */
private val BarToggleDistance = 32.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentState: DocumentState,
    searchState: SearchState,
    pendingRestorePage: Int?,
    onPageRestored: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onSubmitPassword: (password: String, remember: Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenAnother: () -> Unit,
    onOpenSearch: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNextMatch: () -> Unit,
    onPreviousMatch: () -> Unit,
) {
    BackHandler(onBack = if (searchState.isActive) onCloseSearch else onBack)

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
    if (isToolsSheetOpen && loaded != null) {
        ToolsSheet(
            isSearchSupported = loaded.isSearchSupported,
            onDismiss = { isToolsSheetOpen = false },
            onSearch = onOpenSearch,
            onPrint = onPrint,
            onShare = onShare,
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val linkFailedMessage = stringResource(R.string.link_failed)

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
        when (documentState) {
            is DocumentState.Loaded -> Document(
                documentState = documentState,
                searchState = searchState,
                pendingRestorePage = pendingRestorePage,
                onPageRestored = onPageRestored,
                onPageChanged = onPageChanged,
                contentPadding = contentPadding,
                onBarHiddenChange = { isBarHiddenByScroll = it },
                onLinkFailed = {
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(linkFailedMessage)
                    }
                },
            )

            is DocumentState.NeedsPassword -> PasswordPrompt(
                state = documentState,
                onSubmit = onSubmitPassword,
                // The keyboard inset includes the navigation bar, which is already padded.
                modifier = Modifier
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding),
            )

            is DocumentState.Failed -> Failure(
                reason = documentState.reason,
                onOpenAnother = onOpenAnother,
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
                        onClose = onCloseSearch,
                        onQueryChange = onQueryChange,
                        onNext = onNextMatch,
                        onPrevious = onPreviousMatch,
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
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                            }
                        },
                        actions = {
                            if (loaded != null) {
                                IconButton(onClick = { isToolsSheetOpen = true }) {
                                    Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_options))
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
private val BarInsets
    @Composable get() = TopAppBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal)

/** The document's tools, collected in one place to keep the top bar calm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsSheet(
    isSearchSupported: Boolean,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        @Composable
        fun Tool(icon: Int, label: Int, action: () -> Unit) = ListItem(
            headlineContent = { Text(stringResource(label)) },
            leadingContent = { Icon(painterResource(icon), contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                onDismiss()
                action()
            },
        )
        if (isSearchSupported) Tool(R.drawable.ic_search, R.string.search, onSearch)
        Tool(R.drawable.ic_print, R.string.print, onPrint)
        Tool(R.drawable.ic_share, R.string.share, onShare)
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalPdfApi::class)
@Composable
private fun Document(
    documentState: DocumentState.Loaded,
    searchState: SearchState,
    pendingRestorePage: Int?,
    onPageRestored: () -> Unit,
    onPageChanged: (Int) -> Unit,
    contentPadding: PaddingValues,
    onBarHiddenChange: (Boolean) -> Unit,
    onLinkFailed: () -> Unit,
) {
    val context = LocalContext.current
    val viewerState = rememberPdfViewerState()
    var isContentLoaded by remember { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topPaddingPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val toggleDistancePx = with(density) { BarToggleDistance.toPx() }

    LaunchedEffect(viewerState, searchState.matches, searchState.currentIndex) {
        showMatches(viewerState, searchState)
    }

    LaunchedEffect(viewerState, isContentLoaded, pendingRestorePage) {
        if (!isContentLoaded) return@LaunchedEffect
        if (pendingRestorePage != null) {
            viewerState.scrollToPage(pendingRestorePage)
            onPageRestored()
            return@LaunchedEffect
        }
        // Only track after restoring, or the initial page 0 would overwrite the saved page.
        snapshotFlow { viewerState.centerPage(viewportHeight) }
            .distinctUntilChanged()
            .collectLatest { page ->
                delay(PAGE_SAVE_DELAY_MS)
                onPageChanged(page)
            }
    }

    LaunchedEffect(viewerState) {
        var previous: Pair<Int, Float>? = null
        var travelled = 0f
        snapshotFlow { viewerState.firstVisiblePage to viewerState.firstVisiblePageOffset.y }
            .collect { (page, top) ->
                val last = previous
                previous = page to top
                if (page == 0 && top >= topPaddingPx - 1) {
                    // Back at the start of the document.
                    travelled = 0f
                    onBarHiddenChange(false)
                    return@collect
                }
                // Only the user's own scrolling counts, not jumps to search matches or saved pages.
                if (last == null || viewerState.gestureState == PdfViewerState.GESTURE_STATE_IDLE) {
                    return@collect
                }
                // Positive when scrolling down through the document.
                val delta = when {
                    page > last.first -> toggleDistancePx
                    page < last.first -> -toggleDistancePx
                    else -> last.second - top
                }
                if (delta * travelled < 0) travelled = 0f
                travelled += delta
                if (abs(travelled) >= toggleDistancePx) onBarHiddenChange(travelled > 0)
            }
    }

    FastScrollAutoHide(contentLoaded = isContentLoaded)

    PdfViewer(
        pdfDocument = documentState.document,
        state = viewerState,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeight = it.height },
        // Pages start below the bar and scroll behind it and the navigation bar.
        contentPadding = contentPadding,
        onFirstContentLoad = { isContentLoaded = true },
        // Handled here, so links with no app to open them don't fail silently.
        onUrlLinkClicked = { uri ->
            if (!context.openLink(uri)) onLinkFailed()
            true
        },
    )
}

/** The page covering the middle of the viewport: what the user is reading. */
@OptIn(ExperimentalPdfApi::class)
private fun PdfViewerState.centerPage(viewportHeight: Int): Int {
    // Read on every scroll, so snapshotFlow re-evaluates while scrolling within a page.
    firstVisiblePageOffset
    var page = firstVisiblePage
    for (candidate in firstVisiblePage until firstVisiblePage + visiblePagesCount) {
        val top = getVisiblePageOffset(candidate)?.y ?: continue
        if (top <= viewportHeight / 2f) page = candidate
    }
    return page
}

@OptIn(ExperimentalPdfApi::class)
private suspend fun showMatches(viewerState: PdfViewerState, searchState: SearchState) {
    viewerState.setHighlights(
        searchState.matches.flatMapIndexed { index, match ->
            val color = if (index == searchState.currentIndex) CURRENT_MATCH_COLOR else MATCH_COLOR
            match.bounds.map { rect ->
                Highlight(PdfRect(match.pageNum, rect.left, rect.top, rect.right, rect.bottom), color)
            }
        }
    )
    val current = searchState.currentMatch ?: return
    val rect = current.bounds.firstOrNull() ?: return
    viewerState.scrollToPosition(PdfPoint(current.pageNum, rect.centerX(), rect.centerY()))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    searchState: SearchState,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_search))
            }
        },
        title = {
            TextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            if (searchState.query.isNotBlank() && !searchState.isSearching) {
                Text(
                    text = if (searchState.matches.isEmpty()) {
                        stringResource(R.string.no_matches)
                    } else {
                        stringResource(R.string.match_counter, searchState.currentIndex + 1, searchState.matches.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            val hasMatches = searchState.matches.isNotEmpty()
            IconButton(onClick = onPrevious, enabled = hasMatches) {
                Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.previous_match))
            }
            IconButton(onClick = onNext, enabled = hasMatches) {
                Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.next_match))
            }
        },
        windowInsets = BarInsets,
    )
}

@Composable
private fun PasswordPrompt(
    state: DocumentState.NeedsPassword,
    onSubmit: (password: String, remember: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    var isPasswordShown by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSubmit = password.isNotEmpty() && !state.isChecking
    val submit = { if (canSubmit) onSubmit(password, rememberPassword) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.password_title),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            isError = state.isWrongPassword,
            supportingText = if (state.isWrongPassword) {
                { Text(stringResource(R.string.wrong_password)) }
            } else {
                null
            },
            visualTransformation = if (isPasswordShown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { isPasswordShown = !isPasswordShown }) {
                    Icon(
                        painterResource(if (isPasswordShown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        stringResource(if (isPasswordShown) R.string.hide_password else R.string.show_password),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = rememberPassword,
                    onValueChange = { rememberPassword = it },
                    role = Role.Checkbox,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = rememberPassword, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.remember_password), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = submit, enabled = canSubmit, shape = MaterialTheme.shapes.small) {
            if (state.isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.open))
            }
        }
    }
}

@Composable
private fun Failure(
    reason: FailureReason,
    onOpenAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    FailureReason.DAMAGED -> R.string.error_damaged
                    FailureReason.NOT_PDF -> R.string.error_not_pdf
                    FailureReason.NO_PERMISSION -> R.string.error_no_permission
                    FailureReason.UNAVAILABLE -> R.string.error_unavailable
                    FailureReason.PASSWORD_UNSUPPORTED -> R.string.error_password_unsupported
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenAnother, shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.open_another))
        }
    }
}
