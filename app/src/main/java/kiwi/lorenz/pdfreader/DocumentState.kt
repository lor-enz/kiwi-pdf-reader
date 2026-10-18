package kiwi.lorenz.pdfreader

import androidx.pdf.PdfDocument

sealed interface DocumentState {
    data object Empty : DocumentState
    data object Loading : DocumentState
    data class Loaded(
        val document: PdfDocument,
        val name: String,
        /** Opened with a password; other apps can't read the file on their own. */
        val isProtected: Boolean,
    ) : DocumentState
    /** [isWrongPassword] is true after the user entered a password that didn't work. */
    data class NeedsPassword(
        val name: String,
        val isWrongPassword: Boolean,
        val isChecking: Boolean = false,
    ) : DocumentState
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
}
