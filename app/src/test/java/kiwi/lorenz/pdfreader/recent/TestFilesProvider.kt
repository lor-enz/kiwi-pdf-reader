package kiwi.lorenz.pdfreader.recent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves the files in [dir] like a documents provider of another app: content URIs that report
 * name, size and modification time, as the store reads them for picked files.
 */
class TestFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = file(uri)
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return MatrixCursor(columns).apply { addRow(arrayOf(file.name, file.length(), file.lastModified())) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "application/pdf"

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) =
        throw UnsupportedOperationException()

    private fun file(uri: Uri) = File(dir, uri.lastPathSegment!!)

    companion object {
        const val AUTHORITY = "kiwi.lorenz.pdfreader.test.files"
        lateinit var dir: File

        /** Writes [content] to a file of this provider and returns its URI. */
        fun provide(name: String, content: String): Uri {
            File(dir, name).writeText(content)
            return Uri.parse("content://$AUTHORITY/$name")
        }
    }
}
