package kiwi.lorenz.pdfreader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
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
    startActivity(viewIntent(FileProvider.getUriForFile(this, "$packageName.files", file)))
}

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

private fun Context.defaultCheckUri() = FileProvider.getUriForFile(this, "$packageName.files", defaultCheckFile())

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
