package kiwi.lorenz.pdfreader

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
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kiwi.lorenz.pdfreader.ui.HomeScreen
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme
import kotlinx.coroutines.launch

/**
 * The home screen with the recent files list. Keeps its name, as launcher shortcuts on people's
 * home screens point to it. Documents open in [ViewerActivity].
 */
class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            KiwiPDFReaderTheme {
                // Exposes test tags to adb-driven testing (uiautomator), see TestTags.
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    val pickPdf = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let { startActivity(ViewerActivity.pickedIntent(this@MainActivity, it)) } }

                    val recentFiles by viewModel.recentFiles.collectAsState()
                    HomeScreen(
                        recentFiles = recentFiles,
                        thumbnailFile = viewModel::thumbnailFile,
                        onOpenPdf = { pickPdf.launch(arrayOf(PDF_MIME_TYPE)) },
                        onOpenRecent = { startActivity(ViewerActivity.recentIntent(this@MainActivity, it.id)) },
                        pendingRemoval = viewModel.pendingRemoval,
                        onShareRecent = { share(it.uri.toUri(), it.name) },
                        onForgetPassword = viewModel::forgetPassword,
                        onRemoveRecent = viewModel::removeRecent,
                        onUndoRemoval = viewModel::undoRemoval,
                        onRemovalDone = viewModel::finishRemoval,
                    )
                }
            }
        }
    }

    private fun share(uri: Uri, name: String) {
        lifecycleScope.launch {
            runCatchingCancellable { sharePdf(uri, name) }.onFailure {
                Toast.makeText(this@MainActivity, R.string.share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
