package kiwi.lorenz.pdfreader

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import kiwi.lorenz.pdfreader.info.DocumentInfo
import kiwi.lorenz.pdfreader.info.readDocumentInfo
import kiwi.lorenz.pdfreader.recent.PasswordCipher
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The recent files list; documents open in [ViewerActivity]. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecentFilesStore.get(application)

    /** Newest first. */
    val recentFiles: StateFlow<List<RecentFile>> = store.entries

    /** Removed from the list, but its files are kept while the removal can be undone. */
    var pendingRemoval by mutableStateOf<RecentFile?>(null)
        private set

    /** A short message for the home screen's snackbar (a string resource), cleared once shown. */
    var message by mutableStateOf<Int?>(null)
        private set

    init {
        viewModelScope.launch {
            store.cleanup()
            application.deleteOldSharedCopies()
        }
    }

    fun thumbnailFile(entry: RecentFile): File = store.thumbnailFile(entry.id)

    /** A protected entry shows its title and such only when its password is remembered (privacy). */
    suspend fun documentInfo(entry: RecentFile): DocumentInfo {
        // The keystore can be slow, so not on the main thread.
        val password = entry.encryptedPassword?.let { withContext(Dispatchers.Default) { PasswordCipher.decrypt(it) } }
        return getApplication<Application>().readDocumentInfo(entry.uri.toUri(), password)
    }

    fun removeRecent(entry: RecentFile) {
        // One undo at a time: an earlier removal becomes final.
        pendingRemoval?.let(store::discard)
        pendingRemoval = entry
        viewModelScope.launch { store.hide(entry.id) }
    }

    fun undoRemoval(entry: RecentFile) {
        if (pendingRemoval == entry) pendingRemoval = null
        viewModelScope.launch { store.restore(entry) }
    }

    fun finishRemoval(entry: RecentFile) {
        if (pendingRemoval == entry) pendingRemoval = null
        store.discard(entry)
    }

    /** The thumbnail goes too, as it shows protected content. */
    fun forgetPassword(entry: RecentFile) {
        viewModelScope.launch { store.setPassword(entry.id, null) }
    }

    fun showMessage(@StringRes message: Int) {
        this.message = message
    }

    fun onMessageShown() {
        message = null
    }
}
