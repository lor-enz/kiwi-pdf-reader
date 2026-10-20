package kiwi.lorenz.pdfreader.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kiwi.lorenz.pdfreader.PdfDefaultTracker
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SettingsActivity
import kiwi.lorenz.pdfreader.SnackbarMessage
import kiwi.lorenz.pdfreader.pdfDefault
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app stores on the phone, for [SettingsActivity]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecentFilesStore.get(application)

    val recentFiles: StateFlow<List<RecentFile>> = store.entries

    /** For the snackbar. */
    val message = SnackbarMessage()

    /** Which app opens PDFs; Settings always shows it, with a way to change it. */
    val pdfDefault = PdfDefaultTracker(application, message)

    init {
        // Loads the list, which may not be loaded yet after process death.
        viewModelScope.launch { store.cleanup() }
    }

    /** Thumbnails of protected documents go too, see [RecentFilesStore.setPassword]. */
    fun forgetAllPasswords() {
        viewModelScope.launch { store.forgetAllPasswords() }
    }

    fun clearRecentFiles() {
        viewModelScope.launch {
            if (store.clear() > 0) message.show(R.string.open_files_kept)
        }
    }
}
