package kiwi.lorenz.pdfreader.viewer

import androidx.pdf.PdfDocument
import kiwi.lorenz.pdfreader.pdf.OutlineItem

sealed interface DocumentState {
    data object Empty : DocumentState
    data object Loading : DocumentState
    data class Loaded(
        val document: PdfDocument,
        val name: String,
        /** From the document's metadata, shown above the file name. */
        val title: String?,
        /** Opened with a password; other apps can't read the file on their own. */
        val isProtected: Boolean,
        /** The table of contents, empty if it has none. */
        val outline: List<OutlineItem> = emptyList(),
    ) : DocumentState
    /**
     * [wrongAttempts] counts the passwords the user entered that didn't work. Once the right one
     * opened the document, the prompt stays until its lock has opened, then [unlocked] shows.
     */
    data class NeedsPassword(
        val name: String,
        val wrongAttempts: Int = 0,
        val isChecking: Boolean = false,
        val unlocked: Loaded? = null,
    ) : DocumentState {
        val isWrongPassword get() = wrongAttempts > 0
    }
    data class Failed(val reason: FailureReason) : DocumentState
}

enum class FailureReason {
    /** Has a PDF header but couldn't be read, e.g. an interrupted download. */
    DAMAGED,
    /** Some other kind of file. */
    NOT_PDF,
    /** The app that provides the file didn't allow us to read it. */
    NO_PERMISSION,
    /** Moved or deleted. */
    UNAVAILABLE,
    /** Opened before and still looks like a PDF, so most likely a passing failure; worth a retry. */
    TEMPORARY,
    /** Images were shared to make a PDF of, but none could be read. */
    NO_IMAGES,
}
