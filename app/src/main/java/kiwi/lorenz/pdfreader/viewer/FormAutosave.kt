package kiwi.lorenz.pdfreader.viewer

import android.os.ParcelFileDescriptor
import androidx.pdf.EditablePdfDocument
import androidx.pdf.models.FormEditInfo
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Applies the edits to a document's form fields and keeps its filled-in version stored: about a
 * second after the last edit, and whenever [save] is called (in the background, before sharing).
 * Without a recent entry ([entryId] null) the edits stay in the open document only.
 */
class FormAutosave(
    private val document: EditablePdfDocument,
    private val entryId: String?,
    private val store: RecentFilesStore,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var lastEdit: Job? = null
    private var autosave: Job? = null
    // Changed on the main thread, read by saves that may finish elsewhere (closing viewer).
    @Volatile private var editCount = 0
    @Volatile private var savedCount = 0
    @Volatile private var isDiscarded = false

    val hasUnsavedEdits: Boolean get() = !isDiscarded && savedCount != editCount

    /** Applies [edit] after the ones before it; the viewer only reports them. */
    fun apply(edit: FormEditInfo) {
        editCount++
        val previous = lastEdit
        lastEdit = scope.launch {
            previous?.join()
            runCatchingCancellable { document.applyEdit(edit) }
        }
        autosave?.cancel()
        autosave = scope.launch {
            delay(AUTOSAVE_DELAY_MS)
            save()
        }
    }

    /** Stores the edits not stored yet. False if they couldn't be (no entry, storage full). */
    suspend fun save(): Boolean = mutex.withLock {
        lastEdit?.join()
        if (!hasUnsavedEdits) return true
        val id = entryId ?: return false
        val count = editCount
        val saved = store.saveFilled(id, ::writeTo)
        if (saved) savedCount = count
        saved
    }

    /** Writes the document with its filled-in fields to [file]. */
    suspend fun writeTo(file: File) = withContext(Dispatchers.IO) {
        lastEdit?.join()
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE
        ParcelFileDescriptor.open(file, mode).use { destination ->
            // Not closed: closing the handle closes the whole document (beta01).
            document.createWriteHandle().writeTo(destination)
        }
    }

    /** Drops the edits: nothing more is stored. [awaitIdle] waits for a save already running. */
    fun discard() {
        isDiscarded = true
        autosave?.cancel()
    }

    suspend fun awaitIdle() = mutex.withLock {}

    private companion object {
        const val AUTOSAVE_DELAY_MS = 1000L
    }
}
