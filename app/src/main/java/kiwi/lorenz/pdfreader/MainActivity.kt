package kiwi.lorenz.pdfreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import kiwi.lorenz.pdfreader.ui.HomeScreen
import kiwi.lorenz.pdfreader.ui.ViewerScreen
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme

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
                val pickPdf = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { viewModel.open(it) } }
                val openPicker = { pickPdf.launch(arrayOf(PDF_MIME_TYPE)) }

                if (viewModel.documentState == DocumentState.Empty) {
                    HomeScreen(onOpenPdf = openPicker)
                } else {
                    ViewerScreen(
                        documentState = viewModel.documentState,
                        onBack = {
                            if (viewModel.openedFromOtherApp) finish() else viewModel.close()
                        },
                        onOpenAnother = openPicker,
                    )
                }
            }
        }
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { viewModel.open(it, fromOtherApp = true) }
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }
}
