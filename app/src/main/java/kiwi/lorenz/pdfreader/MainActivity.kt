package kiwi.lorenz.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kiwi.lorenz.pdfreader.ui.HomeScreen
import kiwi.lorenz.pdfreader.ui.setAppContent
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

        setAppContent {
            val pickPdf = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { startActivity(ViewerActivity.pickedIntent(this@MainActivity, it)) } }

            // Only while in front: opening an entry moves it to the top, which mustn't show while
            // the viewer slides in. Home takes the new order when it's back.
            val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle(
                minActiveState = Lifecycle.State.RESUMED
            )
            HomeScreen(
                recentFiles = recentFiles,
                thumbnailFile = viewModel::thumbnailFile,
                onOpenPdf = { pickPdf.launch(arrayOf(PDF_MIME_TYPE)) },
                onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                onOpenRecent = { startActivity(ViewerActivity.recentIntent(this@MainActivity, it.id)) },
                pendingRemoval = viewModel.pendingRemoval,
                onShareRecent = { share(it.uri.toUri(), it.name) },
                documentInfo = viewModel::documentInfo,
                onForgetPassword = viewModel::forgetPassword,
                onRemoveRecent = viewModel::removeRecent,
                onUndoRemoval = viewModel::undoRemoval,
                onRemovalDone = viewModel::finishRemoval,
                message = viewModel.message,
                onMessageShown = viewModel::onMessageShown,
            )
        }
    }

    private fun share(uri: Uri, name: String) {
        lifecycleScope.launch {
            runCatchingCancellable { sharePdf(uri, name) }.onFailure {
                viewModel.showMessage(R.string.share_failed)
            }
        }
    }
}
