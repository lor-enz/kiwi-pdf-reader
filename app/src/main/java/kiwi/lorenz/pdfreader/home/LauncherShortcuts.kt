package kiwi.lorenz.pdfreader.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import kiwi.lorenz.pdfreader.AppScope
import kiwi.lorenz.pdfreader.MainActivity
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.ViewerActivity
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.RecentFilesStore
import kiwi.lorenz.pdfreader.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The launcher icon's shortcuts (long press) to the most recent documents, kept in step with the
 * recent files list. A shortcut pinned to the home screen is disabled once its entry has left the
 * list. The shortcut's ID is the entry's.
 */
class LauncherShortcuts(private val context: Context, private val store: RecentFilesStore) {
    /** What a shortcut shows; other changes to an entry (its last page) don't update them. */
    private data class Shown(val id: String, val name: String, val hasThumbnail: Boolean)

    fun start() {
        AppScope.launch(Dispatchers.IO) {
            store.loadedEntries
                .map { entries -> entries.map { Shown(it.id, it.name, it.hasThumbnail) } }
                .distinctUntilChanged()
                .collect { runCatchingCancellable { update(it) } }
        }
    }

    private fun update(entries: List<Shown>) {
        val count = min(MAX_SHORTCUTS, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))
        ShortcutManagerCompat.setDynamicShortcuts(context, entries.take(count).map(::shortcut))

        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }.toSet()
        val (listed, gone) = pinned.partition { id -> entries.any { it.id == id } }
        if (gone.isNotEmpty()) {
            ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_removed))
        }
        // Back after Undo, or renamed; enabling also keeps an entry's pinned shortcut usable.
        val listedShortcuts = entries.filter { it.id in listed }.map(::shortcut)
        if (listedShortcuts.isNotEmpty()) {
            ShortcutManagerCompat.enableShortcuts(context, listedShortcuts)
            ShortcutManagerCompat.updateShortcuts(context, listedShortcuts)
        }
    }

    private fun shortcut(entry: Shown): ShortcutInfoCompat {
        val label = entry.name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { entry.name }
        return ShortcutInfoCompat.Builder(context, entry.id)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(icon(entry)))
            // Back goes to the recent files list, as when the document is opened from there.
            .setIntents(
                arrayOf(
                    Intent(Intent.ACTION_MAIN).setClass(context, MainActivity::class.java),
                    ViewerActivity.recentIntent(context, entry.id).setAction(Intent.ACTION_VIEW),
                )
            )
            .build()
    }

    /**
     * The first page on Penguin Blue, inside the area every launcher's mask leaves visible. Protected
     * documents only have a thumbnail while their password is remembered; others, and documents
     * that haven't opened yet, get the PDF symbol.
     */
    private fun icon(entry: Shown): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (ADAPTIVE_ICON_DP * density).roundToInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.ic_launcher_background))

        val thumbnail = if (entry.hasThumbnail) {
            BitmapFactory.decodeFile(store.thumbnailFile(entry.id).path)
        } else {
            null
        }
        val box = PAGE_BOX_DP * density
        if (thumbnail != null) {
            val scale = min(box / thumbnail.width, box / thumbnail.height)
            val width = thumbnail.width * scale
            val height = thumbnail.height * scale
            val left = (size - width) / 2
            val top = (size - height) / 2
            canvas.drawBitmap(thumbnail, null, RectF(left, top, left + width, top + height), null)
        } else {
            val symbol = checkNotNull(ContextCompat.getDrawable(context, R.drawable.pdf_box)).mutate()
            symbol.setTint(Color.WHITE)
            val inset = ((size - box) / 2).roundToInt()
            symbol.setBounds(inset, inset, size - inset, size - inset)
            symbol.draw(canvas)
        }
        return bitmap
    }

    private companion object {
        /** Launchers show about four in the long-press menu. */
        const val MAX_SHORTCUTS = 4

        /** The full size of an adaptive icon, of which a circle of about 66 dp stays visible. */
        const val ADAPTIVE_ICON_DP = 108

        /** Fits the page's corners into that circle for any page shape. */
        const val PAGE_BOX_DP = 46
    }
}
