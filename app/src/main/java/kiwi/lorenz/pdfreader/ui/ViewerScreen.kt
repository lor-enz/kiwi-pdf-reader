package kiwi.lorenz.pdfreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.rememberPdfViewerState
import kiwi.lorenz.pdfreader.DocumentState
import kiwi.lorenz.pdfreader.FailureReason
import kiwi.lorenz.pdfreader.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    documentState: DocumentState,
    onBack: () -> Unit,
    onOpenAnother: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val loaded = documentState as? DocumentState.Loaded

    Scaffold(
        topBar = {
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
            )
        },
    ) { innerPadding ->
        when (documentState) {
            is DocumentState.Loaded -> Document(
                documentState = documentState,
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
    innerPadding: PaddingValues,
) {
    val viewerState = rememberPdfViewerState()

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
