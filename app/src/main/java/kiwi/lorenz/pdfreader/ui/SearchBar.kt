package kiwi.lorenz.pdfreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SearchState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchBar(
    searchState: SearchState,
    onClose: () -> Unit,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onClose, modifier = Modifier.testTag(TestTags.CLOSE_SEARCH)) {
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
                        .focusRequester(focusRequester)
                        .testTag(TestTags.SEARCH_FIELD),
                )
            },
            actions = {
                if (searchState.query.isNotBlank() && !searchState.isSearching && !searchState.hasNoText) {
                    Text(
                        text = if (searchState.matches.isEmpty()) {
                            stringResource(R.string.no_matches)
                        } else {
                            stringResource(
                                R.string.match_counter,
                                searchState.currentIndex + 1,
                                searchState.matches.size,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .testTag(TestTags.MATCH_COUNTER),
                    )
                }
                val hasMatches = searchState.matches.isNotEmpty()
                IconButton(
                    onClick = onPrevious,
                    enabled = hasMatches,
                    modifier = Modifier.testTag(TestTags.PREVIOUS_MATCH),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.previous_match))
                }
                IconButton(
                    onClick = onNext,
                    enabled = hasMatches,
                    modifier = Modifier.testTag(TestTags.NEXT_MATCH),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.next_match))
                }
            },
            windowInsets = BarInsets,
        )
        // Below the bar: too long for the counter's place, and it explains why nothing is found.
        if (searchState.hasNoText && !searchState.isSearching) {
            Text(
                text = stringResource(R.string.no_text_to_search),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .testTag(TestTags.NO_TEXT_TO_SEARCH),
            )
        }
    }
}
