package kiwi.lorenz.pdfreader.recent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.core.util.readText
import androidx.core.util.writeText
import kiwi.lorenz.pdfreader.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists the recent files list, the private copies, filled-in forms and thumbnails under
 * `files/recent/`, which is excluded from backups.
 *
 * Process-wide singleton: several viewers (one per card in the app switcher) must not write the
 * index concurrently.
 */
class RecentFilesStore @VisibleForTesting internal constructor(private val context: Context) {
    private val root = File(context.filesDir, "recent")
    private val copiesDir = File(root, "copies")
    private val thumbnailsDir = File(root, "thumbnails")
    private val filledDir = File(root, "filled")
    private val index = AtomicFile(File(root, "index.json"))
    private val openTasks = OpenTasks(context, File(root, "open.json"))

    private val mutex = Mutex()
    private var loaded = false

    /** False while the index on disk is behind [entries], see [edit]. */
    private var isSaved = true

    /** For work that must finish even when the screen that started it is gone. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Entries taken off the list whose files are kept until the removal is final, see [hide]. */
    private val hidden = mutableMapOf<String, RecentFile>()

    private val _entries = MutableStateFlow<List<RecentFile>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<RecentFile>> = _entries.asStateFlow()

    suspend fun get(id: String): RecentFile? = edit { entries -> entries.find { it.id == id } }

    /**
     * Records a file from the app's own picker and keeps its read permission. Returns null if the
     * permission can't be kept; the caller then opens the file without a recent entry.
     */
    suspend fun addPicked(uri: Uri, name: String): RecentFile? = edit { entries ->
        // Under the lock, so cleanup can't release the permission before the entry exists.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            return@edit null
        }
        val existing = entries.find { !it.isCopy && it.uri == uri.toString() }
        val entry = existing?.copy(name = name, lastOpenedMillis = now())
            ?: RecentFile(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                name = name,
                isCopy = false,
                lastOpenedMillis = now(),
            )
        entries.upsert(entry)
        entry
    }

    /**
     * Copies a file handed over by another app into private storage. Returns null if the copy
     * fails (e.g. storage full); the caller then opens the original without a recent entry.
     */
    suspend fun importCopy(source: Uri, name: String): RecentFile? = withContext(Dispatchers.IO) {
        copiesDir.mkdirs()
        val temp = File(copiesDir, "import-${UUID.randomUUID()}.tmp")
        val hash = hash(source, copyTo = temp)
        if (hash == null) {
            temp.delete()
            return@withContext null
        }
        edit { entries ->
            // The same file picked before: its entry opens the original, the copy isn't needed.
            val picked = entries.find { !it.isCopy && it.contentHash == hash }
            val isPickedUnchanged = picked != null &&
                stat(picked.uri.toUri()).let { (size, modified) -> picked.isHashCurrent(size, modified) }
            if (picked != null && isPickedUnchanged) {
                temp.delete()
                return@edit picked.copy(lastOpenedMillis = now()).also { entries.upsert(it) }
            }
            val existing = entries.find { it.isCopy && it.contentHash == hash }
            if (existing != null) {
                temp.delete()
                return@edit existing.copy(name = name, lastOpenedMillis = now()).also { entries.upsert(it) }
            }
            val id = UUID.randomUUID().toString()
            val file = copyFile(id)
            if (!temp.renameTo(file)) {
                temp.delete()
                return@edit null
            }
            RecentFile(
                id = id,
                uri = Uri.fromFile(file).toString(),
                name = name,
                isCopy = true,
                contentHash = hash,
                sizeBytes = file.length(),
                lastOpenedMillis = now(),
            ).also { entries.upsert(it) }
        }
    }

    /**
     * Hashes a picked file, so the same PDF opened from another app shares its entry; copies of
     * it are removed. Skipped while the file's size and modification time are unchanged. Reads
     * the whole file, so it runs after the document has opened.
     */
    suspend fun hashPicked(id: String) {
        val entry = get(id)?.takeIf { !it.isCopy } ?: return
        val uri = entry.uri.toUri()
        val (size, modified) = withContext(Dispatchers.IO) { stat(uri) }
        if (entry.isHashCurrent(size, modified)) return
        val hash = hash(uri) ?: return
        edit { entries ->
            val current = entries.find { it.id == id } ?: return@edit
            entries.upsert(current.copy(contentHash = hash, sizeBytes = size ?: 0, modifiedMillis = modified))
            // Duplicates open in another card stay until it's closed.
            val openIds = openTasks.live().values
            entries.filter { it.id != id && it.contentHash == hash && it.id !in openIds }.forEach {
                entries.remove(it)
                // A form filled in through the duplicate is kept, unless this one has its own.
                val picked = entries.first { e -> e.id == id }
                if (it.hasFilledVersion && !picked.hasFilledVersion && filledFile(it.id).renameTo(filledFile(id))) {
                    entries.upsert(picked.copy(filledFromHash = it.filledFromHash, filledBytes = it.filledBytes))
                }
                deleteFiles(it, entries)
            }
        }
    }

    /** Records which entry the viewer in task [taskId] shows, or that it shows none; see [OpenTasks]. */
    suspend fun setOpen(taskId: Int, entryId: String?) = edit { openTasks.set(taskId, entryId) }

    /** The entry the viewer in [taskId] showed, see [setOpen]. */
    suspend fun openIn(taskId: Int): RecentFile? = edit { entries ->
        openTasks.entryIn(taskId)?.let { id -> entries.find { it.id == id } }
    }

    /** A task other than [exceptTaskId] whose viewer shows [entryId], if there is one. */
    suspend fun taskShowing(entryId: String, exceptTaskId: Int): Int? = edit {
        openTasks.live().entries.firstOrNull { it.value == entryId && it.key != exceptTaskId }?.key
    }

    /** The viewer in [taskId] is gone: its entry is no longer protected from [cleanup]. */
    fun closed(taskId: Int) {
        scope.launch {
            setOpen(taskId, null)
            cleanup()
        }
    }

    suspend fun touch(id: String) = update(id) { it.copy(lastOpenedMillis = now()) }

    suspend fun setPageCount(id: String, pageCount: Int) = update(id) { it.copy(pageCount = pageCount) }

    suspend fun setLastPage(id: String, page: Int) = update(id) { it.copy(lastPage = page) }

    /**
     * Remembers or forgets the password of a protected document. Without a remembered password,
     * the thumbnail is dropped too: it would show protected content in the list.
     */
    suspend fun setPassword(id: String, encryptedPassword: String?) {
        if (encryptedPassword == null) withContext(Dispatchers.IO) { thumbnailFile(id).delete() }
        update(id) {
            it.copy(
                isProtected = true,
                encryptedPassword = encryptedPassword,
                hasThumbnail = it.hasThumbnail && encryptedPassword != null,
            )
        }
    }

    /** Forgets every remembered password, see [setPassword]. Also those of [hide]den entries. */
    suspend fun forgetAllPasswords() = edit { entries ->
        fun forget(entry: RecentFile): RecentFile {
            if (entry.encryptedPassword == null) return entry
            thumbnailFile(entry.id).delete()
            return entry.copy(isProtected = true, encryptedPassword = null, hasThumbnail = false)
        }
        entries.replaceAll(::forget)
        hidden.replaceAll { _, entry -> forget(entry) }
    }

    /** The document opened without a password, e.g. after it was replaced by an unprotected one. */
    suspend fun setUnprotected(id: String) = update(id) { it.copy(isProtected = false) }

    fun thumbnailFile(id: String) = File(thumbnailsDir, "$id.webp")

    suspend fun saveThumbnail(id: String, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            thumbnailsDir.mkdirs()
            thumbnailFile(id).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMBNAIL_QUALITY, it)
            }
        }
        update(id) { it.copy(hasThumbnail = true) }
    }

    fun filledFile(id: String) = File(filledDir, "$id.pdf")

    /**
     * Stores the filled-in version of entry [id]'s form; [write] writes the whole document to the
     * file it's given. False if it couldn't be stored, e.g. storage full or the entry is gone.
     */
    suspend fun saveFilled(id: String, write: suspend (File) -> Unit): Boolean {
        val originalHash = originalHash(id) ?: return false
        val temp = File(filledDir, "$id-${UUID.randomUUID()}.tmp")
        try {
            withContext(Dispatchers.IO) { filledDir.mkdirs() }
            val written = runCatchingCancellable { write(temp) }.isSuccess
            return edit { entries ->
                val entry = entries.find { it.id == id }
                if (!written || entry == null || !temp.renameTo(filledFile(id))) return@edit false
                entries.upsert(entry.copy(filledFromHash = originalHash, filledBytes = filledFile(id).length()))
                true
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * The filled-in version to open instead of entry [id]'s original, if there is one. One filled
     * in from an earlier version of the original is dropped: it belongs to a different document.
     */
    suspend fun filledVersion(id: String): File? {
        val entry = get(id)?.takeIf { it.hasFilledVersion } ?: return null
        val file = filledFile(id)
        if (originalHash(id) == entry.filledFromHash && file.exists()) return file
        clearFilled(id)
        return null
    }

    /** Deletes the filled-in version of entry [id]; the original opens again. */
    suspend fun clearFilled(id: String) = edit { entries ->
        filledFile(id).delete()
        entries.find { it.id == id }?.let { entries.upsert(it.copy(filledFromHash = null, filledBytes = 0)) }
        Unit
    }

    /** The current hash of entry [id]'s original. A picked file is hashed again if it changed. */
    private suspend fun originalHash(id: String): String? {
        val entry = get(id) ?: return null
        if (entry.isCopy) return entry.contentHash
        hashPicked(id)
        return get(id)?.contentHash
    }

    suspend fun remove(id: String) = edit { entries ->
        entries.find { it.id == id }?.let { entries.remove(it); deleteFiles(it, entries) }
        Unit
    }

    /**
     * Removes every entry with its files, except documents open in a viewer (as [cleanup] does).
     * Returns how many entries stay.
     */
    suspend fun clear(): Int = edit { entries ->
        val openIds = openTasks.live().values.toSet()
        entries.filter { it.id !in openIds }.forEach { entries.remove(it); deleteFiles(it, entries) }
        entries.size
    }

    /** Takes an entry off the list but keeps its files, so [restore] can undo it until [discard]. */
    suspend fun hide(id: String): RecentFile? = edit { entries ->
        entries.find { it.id == id }?.also {
            entries.remove(it)
            hidden[it.id] = it
        }
    }

    suspend fun restore(entry: RecentFile) = edit { entries ->
        hidden.remove(entry.id)?.let { entries.upsert(it) }
        Unit
    }

    /** Makes the removal of a [hide]den entry final. Finishes even if the caller is cancelled. */
    fun discard(entry: RecentFile) {
        scope.launch {
            edit { entries -> hidden.remove(entry.id)?.let { deleteFiles(it, entries) } }
        }
    }

    /** Applies [CleanupRules], sparing open documents, and deletes files that no entry refers to. */
    suspend fun cleanup() = edit { entries ->
        val plan = CleanupRules.plan(entries, now(), openTasks.live().values.toSet())
        entries.filter { it.id in plan.removeEntries }.forEach { entries.remove(it); deleteFiles(it, entries) }
        entries.filter { it.id in plan.dropFilled }.forEach {
            filledFile(it.id).delete()
            entries.upsert(it.copy(filledFromHash = null, filledBytes = 0))
        }

        val kept = entries + hidden.values
        val ids = kept.map { it.id }.toSet()
        // Imports and saves write a .tmp file outside the lock; only delete those left behind by a
        // crash or a cancelled import.
        fun isOrphan(file: File, ids: Set<String>) = if (file.name.endsWith(".tmp")) {
            now() - file.lastModified() > STALE_IMPORT_MILLIS
        } else {
            file.nameWithoutExtension !in ids
        }
        copiesDir.listFiles()?.filter { isOrphan(it, ids) }?.forEach { it.delete() }
        val filledIds = kept.filter { it.hasFilledVersion }.map { it.id }.toSet()
        filledDir.listFiles()?.filter { isOrphan(it, filledIds) }?.forEach { it.delete() }
        thumbnailsDir.listFiles()?.filter { it.nameWithoutExtension !in ids }?.forEach { it.delete() }
        // Left behind when the app was killed while a removal could still be undone.
        val uris = kept.filter { !it.isCopy }.map { it.uri }.toSet()
        context.contentResolver.persistedUriPermissions
            .filter { it.uri.toString() !in uris }
            .forEach { releasePermission(it.uri) }
    }

    private suspend fun update(id: String, change: (RecentFile) -> RecentFile) = edit { entries ->
        entries.find { it.id == id }?.let { entries.upsert(change(it)) }
        Unit
    }

    /** Runs [block] on the loaded list under the lock, then saves it if it changed. */
    private suspend fun <T> edit(block: (MutableList<RecentFile>) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                _entries.value = load()
                openTasks.load()
                loaded = true
            }
            val entries = _entries.value.toMutableList()
            val result = block(entries)
            val sorted = entries.sortedByDescending { it.lastOpenedMillis }
            if (sorted != _entries.value || !isSaved) {
                _entries.value = sorted
                // When saving fails (storage full), the list still changes; the next edit retries.
                isSaved = save(sorted)
            }
            result
        }
    }

    private fun MutableList<RecentFile>.upsert(entry: RecentFile) {
        removeAll { it.id == entry.id }
        add(entry)
    }

    /** Deletes what belongs to a removed entry. [remaining] are the entries still listed. */
    private fun deleteFiles(entry: RecentFile, remaining: List<RecentFile>) {
        if (entry.isCopy) {
            copyFile(entry.id).delete()
        } else if (remaining.none { it.uri == entry.uri } && hidden.values.none { it.uri == entry.uri }) {
            // Picked again in the meantime, the new entry needs the same permission.
            releasePermission(entry.uri.toUri())
        }
        thumbnailFile(entry.id).delete()
        filledFile(entry.id).delete()
    }

    private fun releasePermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Permission was already gone.
        }
    }

    private fun copyFile(id: String) = File(copiesDir, "$id.pdf")

    /**
     * SHA-256 of the file's content, which is also written to [copyTo] if given. Null if it can't
     * be read or written. Stops when cancelled, as large files take a while.
     */
    private suspend fun hash(uri: Uri, copyTo: File? = null): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read $uri")
            input.use {
                copyTo?.outputStream().use { output ->
                    val buffer = ByteArray(HASH_BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = it.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output?.write(buffer, 0, read)
                    }
                }
            }
            digest.digest().toHex()
        }.getOrNull()
    }

    /** Size and modification time as the provider reports them; null where unknown. */
    private fun stat(uri: Uri): Pair<Long?, Long?> = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun long(column: String) = cursor.getColumnIndex(column)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
            long(OpenableColumns.SIZE) to long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        } ?: (null to null)
    } catch (_: Exception) {
        // E.g. the permission is gone.
        null to null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun load(): List<RecentFile> {
        val array = try {
            JSONArray(index.readText())
        } catch (_: Exception) {
            // Missing or unreadable index: start with an empty list.
            return emptyList()
        }
        // An entry that can't be read is dropped on its own, not the whole list with it.
        return (0 until array.length()).mapNotNull { i ->
            try {
                array.getJSONObject(i).toRecentFile()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** False if the index couldn't be written. */
    private fun save(entries: List<RecentFile>): Boolean = try {
        root.mkdirs()
        // Synced before it replaces the old index, so a crash or power loss can't leave half a file.
        index.writeText(JSONArray(entries.map { it.toJson() }).toString())
        true
    } catch (_: IOException) {
        false
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val THUMBNAIL_QUALITY = 80
        private const val STALE_IMPORT_MILLIS = 60L * 60 * 1000
        private const val HASH_BUFFER_BYTES = 64 * 1024

        // Holds only the application context, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: RecentFilesStore? = null

        fun get(context: Context): RecentFilesStore = instance ?: synchronized(this) {
            instance ?: RecentFilesStore(context.applicationContext).also { instance = it }
        }
    }
}
