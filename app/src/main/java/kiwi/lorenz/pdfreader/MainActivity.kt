package kiwi.lorenz.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kiwi.lorenz.pdfreader.ui.HomeScreen
import kiwi.lorenz.pdfreader.ui.ViewerScreen
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // On rotation the ViewModel still holds the document, so only handle fresh launches.
        if (savedInstanceState == null) handleIntent(intent)
        addOnNewIntentListener(::handleIntent)

        setContent {
            KiwiPDFReaderTheme {
                // Exposes test tags to adb-driven testing (uiautomator), see TestTags.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    val pickPdf = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let { viewModel.openPicked(it) } }
                    val openPicker = { pickPdf.launch(arrayOf(PDF_MIME_TYPE)) }

                    if (viewModel.documentState == DocumentState.Empty) {
                        val recentFiles by viewModel.recentFiles.collectAsState()
                        HomeScreen(
                            recentFiles = recentFiles,
                            thumbnailFile = viewModel::thumbnailFile,
                            onOpenPdf = openPicker,
                            onOpenRecent = viewModel::openRecent,
                            pendingRemoval = viewModel.pendingRemoval,
                            onShareRecent = { share(it.uri.toUri(), it.name) },
                            onRemoveRecent = viewModel::removeRecent,
                            onUndoRemoval = viewModel::undoRemoval,
                            onRemovalDone = viewModel::finishRemoval,
                        )
                    } else {
                        ViewerScreen(
                            documentState = viewModel.documentState,
                            searchState = viewModel.searchState,
                            pendingRestorePage = viewModel.pendingRestorePage,
                            onPageRestored = viewModel::onPageRestored,
                            onPageChanged = viewModel::onPageChanged,
                            onSubmitPassword = viewModel::submitPassword,
                            onBack = {
                                if (viewModel.openedFromOtherApp) finish() else viewModel.close()
                            },
                            onOpenAnother = openPicker,
                            onOpenSearch = viewModel::openSearch,
                            onPrint = { loadedDocument()?.let { printPdf(it.document, it.name, it.isProtected, viewModel.viewModelScope) } },
                            onShare = { loadedDocument()?.let { share(it.document.uri, it.name) } },
                            onCloseSearch = viewModel::closeSearch,
                            onQueryChange = viewModel::updateQuery,
                            onNextMatch = viewModel::nextMatch,
                            onPreviousMatch = viewModel::previousMatch,
                        )
                    }
                }
            }
        }
    }

    private fun loadedDocument() = viewModel.documentState as? DocumentState.Loaded

    private fun share(uri: Uri, name: String) {
        lifecycleScope.launch {
            try {
                sharePdf(uri, name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(this@MainActivity, R.string.share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { viewModel.openFromOtherApp(it) }
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }
}
