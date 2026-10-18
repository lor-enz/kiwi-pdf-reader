package kiwi.lorenz.pdfreader.recent

import android.app.ActivityManager
import android.content.Context
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Which recent entry the viewer in each task (card in the app switcher) shows, by task id.
 * Saved in [file], so cleanup leaves open documents alone and a card can reopen its document,
 * also after process death. Not thread-safe: [RecentFilesStore] uses it under its lock.
 */
internal class OpenTasks(private val context: Context, file: File) {
    private val file = AtomicFile(file)
    private val byTask = mutableMapOf<Int, String>()

    fun load() {
        byTask += try {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associate { it.toInt() to json.getString(it) }
        } catch (_: Exception) {
            // Missing or unreadable: nothing is known to be open.
            emptyMap()
        }
    }

    /** Records that the viewer in [taskId] shows [entryId], or nothing. */
    fun set(taskId: Int, entryId: String?) {
        val changed = if (entryId == null) byTask.remove(taskId) != null else byTask.put(taskId, entryId) != entryId
        if (changed) save()
    }

    /** What the viewer in [taskId] showed, even if the app was killed since. */
    fun entryIn(taskId: Int): String? = byTask[taskId]

    /**
     * The open entries by task, without tasks that are gone, e.g. a card swiped away while the
     * app wasn't running.
     */
    fun live(): Map<Int, String> {
        if (byTask.keys.retainAll(context.appTaskIds())) save()
        return byTask
    }

    /** Failing is fine: open documents then just lose their protection from cleanup. */
    private fun save() {
        try {
            file.baseFile.parentFile?.mkdirs()
            file.writeText(JSONObject(byTask.mapKeys { it.key.toString() }).toString())
        } catch (_: IOException) {
        }
    }
}

/** The ids of this app's tasks. Only its own tasks are listed, so this needs no permission. */
fun Context.appTaskIds(): Set<Int> = appTasks().keys

/** This app's tasks by id. A task that's just going away can't be read and is left out. */
fun Context.appTasks(): Map<Int, ActivityManager.AppTask> =
    getSystemService(ActivityManager::class.java).appTasks
        .mapNotNull { task -> runCatching { task.taskInfo?.taskId }.getOrNull()?.let { it to task } }
        .toMap()
