package kiwi.lorenz.pdfreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Which app opens PDFs from other apps. Android has no way to ask to become the default for a
 * file type: only "Always" in the system's "Open with" dialog sets it, and only the default
 * app's own "Open by default" settings clear it.
 */
sealed interface PdfDefault {
    data object ThisApp : PdfDefault

    /** No default: the system asks each time, so [askToBecomePdfDefault] can offer "Always". */
    data object None : PdfDefault

    /** Another app opens them; [openDefaultSettings] leads to where that can be cleared. */
    data class OtherApp(val packageName: String) : PdfDefault
}

/** Needs the `<queries>` entry in the manifest to see other apps. */
fun Context.pdfDefault(): PdfDefault {
    val intent = viewIntent(defaultCheckUri())
    val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return PdfDefault.None
    if (resolved.packageName == packageName) return PdfDefault.ThisApp
    // Without a default it resolves to the system's "Open with" dialog, which doesn't handle PDFs.
    val isHandler = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
        it.activityInfo.packageName == resolved.packageName && it.activityInfo.name == resolved.name
    }
    if (!isHandler) return PdfDefault.None
    return PdfDefault.OtherApp(resolved.packageName)
}

/**
 * Opens a small PDF of the app's own, so the system shows its "Open with" dialog with "Always".
 * Only makes sense with [PdfDefault.None]: with a default, that app would open it. The viewer
 * closes right away when it gets this file ([isDefaultCheck]).
 */
suspend fun Context.askToBecomePdfDefault() {
    val file = withContext(Dispatchers.IO) { defaultCheckFile().also { if (!it.exists()) writeDefaultCheckPdf(it) } }
    startActivity(viewIntent(providedUri(file)))
}

/**
 * Which app opens PDFs, for a screen that shows the hint; checked again on every resume. Knows
 * whether the "Open with" dialog was shown, as becoming the default then deserves a confirmation
 * in [message].
 */
class PdfDefaultTracker(private val context: Context, private val message: SnackbarMessage) {
    var pdfDefault by mutableStateOf<PdfDefault>(PdfDefault.ThisApp)
        private set

    /** "Not now" on Home's hint, which then stays away for [HINT_PAUSE_MILLIS]. */
    var isHintPaused by mutableStateOf(false)
        private set

    private var isAsking = false

    /**
     * Checks on every resume of [activity]: before the first frame, so the hint doesn't flash,
     * and again after the "Open with" dialog or the other app's settings.
     */
    fun observe(activity: ComponentActivity) {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        })
    }

    /** Shows the "Open with" dialog, see [askToBecomePdfDefault]. */
    fun ask(activity: ComponentActivity) {
        isAsking = true
        activity.lifecycleScope.launch { activity.askToBecomePdfDefault() }
    }

    private fun refresh() {
        pdfDefault = context.pdfDefault()
        val pausedAt = context.hintPreferences().getLong(HINT_PAUSED_AT, 0)
        isHintPaused = System.currentTimeMillis() - pausedAt in 0..<HINT_PAUSE_MILLIS
        if (isAsking && pdfDefault == PdfDefault.ThisApp) message.show(R.string.now_default_pdf_app)
        isAsking = false
    }

    fun pauseHint() {
        context.hintPreferences().edit { putLong(HINT_PAUSED_AT, System.currentTimeMillis()) }
        isHintPaused = true
    }
}

private fun Context.hintPreferences() = getSharedPreferences("default_hint", Context.MODE_PRIVATE)

/** The file opened by [askToBecomePdfDefault]. */
fun Context.isDefaultCheck(uri: Uri?) = uri == defaultCheckUri()

/** The other app's "Open by default" page, which has "Clear default preferences". */
fun Context.openDefaultSettings(packageName: String) {
    val uri = "package:$packageName".toUri()
    try {
        startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri))
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
    }
}

private fun viewIntent(uri: Uri) = Intent(Intent.ACTION_VIEW)
    .setDataAndType(uri, PDF_MIME_TYPE)
    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

private fun Context.defaultCheckFile() = File(File(cacheDir, DEFAULT_CHECK_DIR), "PDF Penguin.pdf")

private fun Context.defaultCheckUri() = providedUri(defaultCheckFile())

/** Another app opens it if the user picks that one instead, so it shows the app's name. */
private fun writeDefaultCheckPdf(file: File) {
    file.parentFile!!.mkdirs()
    val document = PdfDocument()
    // A4 in points.
    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }
    page.canvas.drawText("PDF Penguin", 595 / 2f, 842 / 2f, paint)
    document.finishPage(page)
    file.outputStream().use(document::writeTo)
    document.close()
}

private const val DEFAULT_CHECK_DIR = "default-check"
private const val HINT_PAUSED_AT = "paused_at"
private const val HINT_PAUSE_MILLIS = 7 * 24 * 60 * 60 * 1000L
