package kiwi.lorenz.pdfreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
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

// PdfView draws highlights with a MULTIPLY blend, so these must be opaque and light:
// pale tints of the brand green / brown that keep the text underneath readable.
private const val MATCH_COLOR = 0xFFDCE3A6.toInt()
private const val CURRENT_MATCH_COLOR = 0xFFF2B48C.toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentState: DocumentState,
    searchState: SearchState,
    onBack: () -> Unit,
    onOpenAnother: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNextMatch: () -> Unit,
    onPreviousMatch: () -> Unit,
) {
    BackHandler(onBack = if (searchState.isActive) onCloseSearch else onBack)

    val loaded = documentState as? DocumentState.Loaded

    Scaffold(
        topBar = {
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
                            text = loaded?.name.orEmpty(),
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
                        if (loaded?.isSearchSupported == true) {
                            IconButton(onClick = onOpenSearch) {
                                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.search))
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        when (documentState) {
            is DocumentState.Loaded -> Document(
                documentState = documentState,
                searchState = searchState,
                innerPadding = innerPadding,
            )

            is DocumentState.Failed -> Failure(
                reason = documentState.reason,
                onOpenAnother = onOpenAnother,
                modifier = Modifier.padding(innerPadding),
            )

            DocumentState.Loading, DocumentState.Empty -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@OptIn(ExperimentalPdfApi::class)
@Composable
private fun Document(
    documentState: DocumentState.Loaded,
    searchState: SearchState,
    innerPadding: PaddingValues,
) {
    val viewerState = rememberPdfViewerState()

    LaunchedEffect(viewerState, searchState.matches, searchState.currentIndex) {
        showMatches(viewerState, searchState)
    }

    PdfViewer(
        pdfDocument = documentState.document,
        state = viewerState,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding()),
        // Let pages scroll behind the navigation bar instead of stopping above it.
        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding()),
    )
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
    )
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
                    FailureReason.PASSWORD_PROTECTED -> R.string.error_password_protected
                    FailureReason.UNREADABLE -> R.string.error_unreadable
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenAnother) {
            Text(stringResource(R.string.open_another))
        }
    }
}
