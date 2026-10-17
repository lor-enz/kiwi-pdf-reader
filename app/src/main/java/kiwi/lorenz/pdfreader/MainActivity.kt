package kiwi.lorenz.pdfreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import kiwi.lorenz.pdfreader.ui.HomeScreen
import kiwi.lorenz.pdfreader.ui.ViewerScreen
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                        onBack = viewModel::close,
                        onOpenAnother = openPicker,
                    )
                }
            }
        }
    }

    private companion object {
        const val PDF_MIME_TYPE = "application/pdf"
    }
}
