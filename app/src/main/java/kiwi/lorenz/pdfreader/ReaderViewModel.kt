package kiwi.lorenz.pdfreader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DocumentState {
    data object Empty : DocumentState
    data object Loading : DocumentState
    data class Loaded(
        val document: PdfDocument,
        val name: String,
    ) : DocumentState
    data class Failed(val reason: FailureReason) : DocumentState
}

enum class FailureReason { PASSWORD_PROTECTED, UNREADABLE }

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val loader = SandboxedPdfLoader(application)

    var documentState by mutableStateOf<DocumentState>(DocumentState.Empty)
        private set

    /** True when another app handed us the PDF; closing it should return to that app. */
    var openedFromOtherApp = false
        private set

    private var loadJob: Job? = null

    fun open(uri: Uri, fromOtherApp: Boolean = false) {
        openedFromOtherApp = fromOtherApp
        closeDocument()
        documentState = DocumentState.Loading
        loadJob = viewModelScope.launch {
            documentState = try {
                val document = loader.openDocument(uri)
                DocumentState.Loaded(
                    document = document,
                    name = displayName(uri),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: PdfPasswordException) {
                DocumentState.Failed(FailureReason.PASSWORD_PROTECTED)
            } catch (_: Exception) {
                // IOException, SecurityException (permission lost), corrupt files, ...
                DocumentState.Failed(FailureReason.UNREADABLE)
            }
        }
    }

    fun close() {
        closeDocument()
        documentState = DocumentState.Empty
        openedFromOtherApp = false
    }

    private fun closeDocument() {
        loadJob?.cancel()
        (documentState as? DocumentState.Loaded)?.document?.close()
    }

    private suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        }
        name ?: uri.lastPathSegment ?: "PDF"
    }

    override fun onCleared() {
        closeDocument()
    }
}
