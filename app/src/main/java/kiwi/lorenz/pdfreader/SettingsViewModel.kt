package kiwi.lorenz.pdfreader

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app stores on the phone, for [SettingsActivity]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecentFilesStore.get(application)

    val recentFiles: StateFlow<List<RecentFile>> = store.entries

    /** A short message for the snackbar (a string resource), cleared once shown. */
    var message by mutableStateOf<Int?>(null)
        private set

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
            if (store.clear() > 0) message = R.string.open_files_kept
        }
    }

    fun showMessage(@StringRes message: Int) {
        this.message = message
    }

    fun onMessageShown() {
        message = null
    }
}
