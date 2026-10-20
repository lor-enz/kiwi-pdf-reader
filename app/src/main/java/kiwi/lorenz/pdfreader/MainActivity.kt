package kiwi.lorenz.pdfreader

import android.content.Intent
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
import kiwi.lorenz.pdfreader.defaultapp.openDefaultSettings
import kiwi.lorenz.pdfreader.document.PDF_MIME_TYPE
import kiwi.lorenz.pdfreader.document.launchSharePdf
import kiwi.lorenz.pdfreader.home.HomeScreen
import kiwi.lorenz.pdfreader.home.HomeViewModel
import kiwi.lorenz.pdfreader.ui.setAppContent

/**
 * The home screen with the recent files list. Keeps its name, as launcher shortcuts on people's
 * home screens point to it. Documents open in [ViewerActivity].
 */
class MainActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.pdfDefault.observe(this)

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
                pdfDefault = viewModel.pdfDefault.pdfDefault,
                onMakeDefault = { viewModel.pdfDefault.ask(this@MainActivity) },
                onChangeDefault = { openDefaultSettings(it.packageName) },
                isHintPaused = viewModel.pdfDefault.isHintPaused,
                onPauseHint = viewModel.pdfDefault::pauseHint,
                onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                onOpenRecent = { startActivity(ViewerActivity.recentIntent(this@MainActivity, it.id)) },
                pendingRemoval = viewModel.pendingRemoval,
                onShareRecent = { entry -> launchSharePdf(entry.name, viewModel.message) { entry.uri.toUri() } },
                documentInfo = viewModel::documentInfo,
                onForgetPassword = viewModel::forgetPassword,
                onRemoveRecent = viewModel::removeRecent,
                onUndoRemoval = viewModel::undoRemoval,
                onRemovalDone = viewModel::finishRemoval,
                message = viewModel.message.current,
                onMessageShown = viewModel.message::shown,
            )
        }
    }
}
