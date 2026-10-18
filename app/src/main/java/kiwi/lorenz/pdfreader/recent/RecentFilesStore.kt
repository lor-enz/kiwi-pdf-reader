package kiwi.lorenz.pdfreader.recent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Persists the recent files list, the private copies and their thumbnails under
 * `files/recent/`, which is excluded from backups.
 *
 * Process-wide singleton: several activity instances (e.g. one opened from WhatsApp, one from
 * the launcher) must not write the index concurrently.
 */
class RecentFilesStore private constructor(private val context: Context) {
    private val root = File(context.filesDir, "recent")
    private val copiesDir = File(root, "copies")
    private val thumbnailsDir = File(root, "thumbnails")
    private val indexFile = File(root, "index.json")

    private val mutex = Mutex()
    private var loaded = false

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
        val hash = try {
            copyHashing(source, temp)
        } catch (_: Exception) {
            temp.delete()
            return@withContext null
        }
        edit { entries ->
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

    suspend fun touch(id: String) = update(id) { it.copy(lastOpenedMillis = now()) }

    suspend fun setPageCount(id: String, pageCount: Int) = update(id) { it.copy(pageCount = pageCount) }

    suspend fun setLastPage(id: String, page: Int) = update(id) { it.copy(lastPage = page) }

    /**
     * Records that a document needed a password. Without a remembered password, the thumbnail is
     * dropped too: it would show protected content in the list.
     */
    suspend fun setPassword(id: String, encryptedPassword: String?) {
        if (encryptedPassword == null) withContext(Dispatchers.IO) { thumbnailFile(id).delete() }
        update(id) {
            it.copy(
                isPasswordProtected = true,
                encryptedPassword = encryptedPassword,
                hasThumbnail = it.hasThumbnail && encryptedPassword != null,
            )
        }
    }

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

    suspend fun remove(id: String) = edit { entries ->
        entries.find { it.id == id }?.let { entries.remove(it); deleteFiles(it, entries) }
        Unit
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

    /** Applies [CleanupRules] and deletes files that no entry refers to. */
    suspend fun cleanup(openId: String?) = edit { entries ->
        val toRemove = CleanupRules.entriesToRemove(entries, now(), openId)
        entries.filter { it.id in toRemove }.forEach { entries.remove(it); deleteFiles(it, entries) }

        val kept = entries + hidden.values
        val ids = kept.map { it.id }.toSet()
        // Imports write a .tmp file outside the lock; only delete those left behind by a crash.
        fun isOrphan(file: File) = if (file.name.endsWith(".tmp")) {
            now() - file.lastModified() > STALE_IMPORT_MILLIS
        } else {
            file.nameWithoutExtension !in ids
        }
        copiesDir.listFiles()?.filter(::isOrphan)?.forEach { it.delete() }
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
                loaded = true
            }
            val entries = _entries.value.toMutableList()
            val result = block(entries)
            val sorted = entries.sortedByDescending { it.lastOpenedMillis }
            if (sorted != _entries.value) {
                save(sorted)
                _entries.value = sorted
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
    }

    private fun releasePermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Permission was already gone.
        }
    }

    private fun copyFile(id: String) = File(copiesDir, "$id.pdf")

    private fun copyHashing(source: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("Cannot read $source")
        DigestInputStream(input, digest).use { hashing ->
            target.outputStream().use { hashing.copyTo(it) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun load(): List<RecentFile> = try {
        val array = JSONArray(indexFile.readText())
        (0 until array.length()).map { array.getJSONObject(it).toRecentFile() }
    } catch (_: Exception) {
        // Missing or unreadable index: start with an empty list.
        emptyList()
    }

    private fun save(entries: List<RecentFile>) {
        root.mkdirs()
        val temp = File(root, "index.json.tmp")
        temp.writeText(JSONArray(entries.map { it.toJson() }).toString())
        temp.renameTo(indexFile)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val THUMBNAIL_QUALITY = 80
        private const val STALE_IMPORT_MILLIS = 60L * 60 * 1000

        @Volatile
        private var instance: RecentFilesStore? = null

        fun get(context: Context): RecentFilesStore = instance ?: synchronized(this) {
            instance ?: RecentFilesStore(context.applicationContext).also { instance = it }
        }
    }
}

private fun RecentFile.toJson() = JSONObject()
    .put("id", id)
    .put("uri", uri)
    .put("name", name)
    .put("isCopy", isCopy)
    .put("contentHash", contentHash)
    .put("sizeBytes", sizeBytes)
    .put("lastOpenedMillis", lastOpenedMillis)
    .put("pageCount", pageCount)
    .put("lastPage", lastPage)
    .put("hasThumbnail", hasThumbnail)
    .put("isPasswordProtected", isPasswordProtected)
    .put("encryptedPassword", encryptedPassword)

private fun JSONObject.toRecentFile() = RecentFile(
    id = getString("id"),
    uri = getString("uri"),
    name = getString("name"),
    isCopy = getBoolean("isCopy"),
    contentHash = optString("contentHash").ifEmpty { null },
    sizeBytes = optLong("sizeBytes"),
    lastOpenedMillis = getLong("lastOpenedMillis"),
    pageCount = optInt("pageCount"),
    lastPage = optInt("lastPage"),
    hasThumbnail = optBoolean("hasThumbnail"),
    isPasswordProtected = optBoolean("isPasswordProtected"),
    encryptedPassword = optString("encryptedPassword").ifEmpty { null },
)
