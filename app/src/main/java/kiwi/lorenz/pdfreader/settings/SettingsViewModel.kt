package kiwi.lorenz.pdfreader.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.SettingsActivity
import kiwi.lorenz.pdfreader.defaultapp.PdfDefaultTracker
import kiwi.lorenz.pdfreader.recent.CleanupLimits
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kiwi.lorenz.pdfreader.util.AppScope
import kiwi.lorenz.pdfreader.util.SnackbarMessage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the app stores on the phone, for [SettingsActivity]. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = RecentFilesStore.get(application)

    val recentFiles: StateFlow<List<RecentFile>> = store.entries

    /** The limits applied. */
    val cleanupLimits: StateFlow<CleanupLimits> = store.limits

    /** Stricter limits, applied once their removals can no longer be undone. */
    var pendingLimits by mutableStateOf<PendingLimits?>(null)
        private set

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

    /** Limits that would remove something wait for [applyLimits] or [undoLimits]; others apply right away. */
    fun chooseLimits(limits: CleanupLimits) {
        viewModelScope.launch {
            val cleanup = store.previewCleanup(limits)
            val dropped = cleanup.dropFilled.size
            if (cleanup.removeEntries.isEmpty() && dropped == 0) {
                pendingLimits = null
                store.setLimits(limits)
            } else {
                // Replaces an earlier choice, which was never applied.
                pendingLimits = PendingLimits(limits, cleanup.removeEntries.size, dropped)
            }
        }
    }

    fun undoLimits(pending: PendingLimits) {
        if (pendingLimits == pending) pendingLimits = null
    }

    /** Finishes even if the screen is gone. */
    fun applyLimits(pending: PendingLimits) {
        if (pendingLimits != pending) return
        pendingLimits = null
        AppScope.launch { store.setLimits(pending.limits) }
    }

    fun clearRecentFiles() {
        viewModelScope.launch {
            if (store.clear() > 0) message.show(R.string.open_files_kept)
        }
    }
}

/**
 * Limits chosen but not yet applied, and what they would remove: entries, and filled-in versions
 * of picked files. Not a data class, so choosing the same limits again is a new choice.
 */
class PendingLimits(val limits: CleanupLimits, val removedEntries: Int, val droppedFilled: Int)
