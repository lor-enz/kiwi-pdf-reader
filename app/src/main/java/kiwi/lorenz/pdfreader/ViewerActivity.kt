package kiwi.lorenz.pdfreader

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import androidx.pdf.models.FormEditInfo
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kiwi.lorenz.pdfreader.defaultapp.isDefaultCheck
import kiwi.lorenz.pdfreader.document.PDF_MIME_TYPE
import kiwi.lorenz.pdfreader.document.launchSharePdf
import kiwi.lorenz.pdfreader.document.pdfFileName
import kiwi.lorenz.pdfreader.document.printPdf
import kiwi.lorenz.pdfreader.recent.appTasks
import kiwi.lorenz.pdfreader.ui.setAppContent
import kiwi.lorenz.pdfreader.viewer.DocumentState
import kiwi.lorenz.pdfreader.viewer.ViewerActions
import kiwi.lorenz.pdfreader.viewer.ViewerScreen
import kiwi.lorenz.pdfreader.viewer.ViewerViewModel
import kotlinx.coroutines.launch

/**
 * Shows one document. Opened from the recent files list, it's part of the app's main task;
 * documents from other apps open in [ExternalViewerActivity], with a card of their own.
 */
open class ViewerActivity : ComponentActivity() {
    private val viewModel: ViewerViewModel by viewModels()

    private val isOwnTask get() = this is ExternalViewerActivity

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.openPicked(it) }
    }

    private val saveCopy = registerForActivityResult(ActivityResultContracts.CreateDocument(PDF_MIME_TYPE)) { uri ->
        uri?.let { viewModel.saveCopy(it) }
    }

    private val actions = object : ViewerActions {
        override fun back() = close()
        override fun openAnother() = pickPdf.launch(arrayOf(PDF_MIME_TYPE))
        override fun retry() = viewModel.retry()
        override fun submitPassword(password: String, remember: Boolean) = viewModel.submitPassword(password, remember)
        override fun unlockFinished() = viewModel.unlockFinished()
        override fun pageRestored() = viewModel.onPageRestored()
        override fun pageChanged(page: Int) = viewModel.onPageChanged(page)
        override fun startPresentation(page: Int) = viewModel.startPresentation(page)
        override fun presentationPageChanged(page: Int) = viewModel.onPresentationPageChanged(page)
        override fun endPresentation() = viewModel.endPresentation()
        override fun formEdited(edit: FormEditInfo) = viewModel.applyFormEdit(edit)
        override fun openSearch() = viewModel.search.open()
        override fun closeSearch() = viewModel.search.close()
        override fun changeQuery(query: String) = viewModel.search.changeQuery(query)
        override fun nextMatch() = viewModel.search.next()
        override fun previousMatch() = viewModel.search.previous()
        override fun print() = this@ViewerActivity.print()
        override fun share() = this@ViewerActivity.share()
        override fun saveCopy() {
            loadedDocument()?.let { saveCopy.launch(pdfFileName(it.name)) }
        }
        override fun clearFilledFields() = viewModel.clearFilledFields()
        override fun goHome() = this@ViewerActivity.goHome()
        override suspend fun documentInfo() = viewModel.documentInfo()
        override fun createdNoticeShown() = viewModel.createdNoticeShown()
        override fun showMessage(message: Int) = viewModel.message.show(message)
        override fun messageShown() = viewModel.message.shown()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.taskId = taskId

        // Only opened to show the "Open with" dialog; Home sees what was chosen.
        if (isDefaultCheck(intent.data)) {
            close()
            return
        }

        val isOpening = when {
            savedInstanceState == null -> handleIntent(intent)
            // Recreated after process death; on rotation the view model still has the document.
            viewModel.documentState == DocumentState.Empty -> viewModel.restore() || handleIntent(intent)
            else -> true
        }
        // E.g. a share without a file in it.
        if (!isOpening) {
            close()
            return
        }

        setAppContent {
            viewModel.shownInTask?.let { task ->
                LaunchedEffect(task) { switchTo(task) }
            }
            val name = loadedDocument()?.name
            if (isOwnTask && name != null) {
                // The card in the app switcher shows the file name.
                LaunchedEffect(name) {
                    setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(name).build())
                }
            }

            ViewerScreen(
                documentState = viewModel.documentState,
                searchState = viewModel.search.state,
                pendingRestorePage = viewModel.pendingRestorePage,
                presentationPage = viewModel.presentationPage,
                isFilledIn = viewModel.isFilledIn,
                createdNotice = viewModel.createdNotice,
                message = viewModel.message.current,
                actions = actions,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onBackground()
    }

    override fun onRestart() {
        super.onRestart()
        viewModel.onReturned()
    }

    private fun loadedDocument() = viewModel.documentState as? DocumentState.Loaded

    /** A card of its own goes away with its document, back to the app that opened it. */
    private fun close() {
        if (isOwnTask) finishAndRemoveTask() else finish()
    }

    /**
     * Home is right behind a viewer in the main task. A card of its own stays in the app switcher
     * with its document; Home comes up in the main task instead, closing any viewer there.
     */
    private fun goHome() {
        if (!isOwnTask) {
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }

    /** The document is already open in [task]'s viewer: shows that one instead of this one. */
    private fun switchTo(task: Int) {
        appTasks()[task]?.moveToFront()
        close()
    }

    /** Filled-in form fields included, as with print and save a copy. */
    private fun share() {
        val name = loadedDocument()?.name ?: return
        launchSharePdf(name, viewModel.message) { checkNotNull(viewModel.currentFile()) }
    }

    private fun print() {
        val loaded = loadedDocument() ?: return
        lifecycleScope.launch {
            val file = viewModel.currentFile() ?: return@launch
            printPdf(loaded.document, file, loaded.name, loaded.isProtected, viewModel.viewModelScope)
        }
    }

    /** False if the intent has nothing to open. */
    private fun handleIntent(intent: Intent): Boolean {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(intent.data)
        }
        // Shared images are made into a PDF.
        val isImages = intent.type?.startsWith("image/") == true
        when {
            entryId != null -> viewModel.openRecent(entryId)
            uris.isEmpty() -> return false
            isOwnTask && isImages -> viewModel.openImages(uris)
            isOwnTask -> viewModel.openFromOtherApp(uris.first())
            else -> viewModel.openPicked(uris.first())
        }
        return true
    }

    companion object {
        private const val EXTRA_ENTRY_ID = "entryId"

        /** Opens an entry of the recent files list. */
        fun recentIntent(context: Context, entryId: String) =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_ENTRY_ID, entryId)

        /** Opens a file chosen with the app's own picker, passing on the permission to read it. */
        fun pickedIntent(context: Context, uri: Uri) =
            Intent(context, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }
}

/**
 * Documents from other apps ("Open with", "Share to"). Each gets a card of its own in the app
 * switcher (`documentLaunchMode`), which goes away when the document is closed.
 */
class ExternalViewerActivity : ViewerActivity()
