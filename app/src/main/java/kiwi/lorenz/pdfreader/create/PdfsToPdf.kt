package kiwi.lorenz.pdfreader.create

import android.content.Context
import android.net.Uri
import kiwi.lorenz.pdfreader.pdf.PdfFile
import kiwi.lorenz.pdfreader.pdf.readSource
import kiwi.lorenz.pdfreader.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

/** Why PDFs couldn't be merged: [name] couldn't be, and so couldn't [others] more. */
data class MergeFailure(val reason: Reason, val name: String, val others: Int) {
    enum class Reason {
        /** Protected, and no password is remembered for it (or it no longer works). */
        NEEDS_PASSWORD,
        /** Damaged, not a PDF, or the app that shared it doesn't let us read it. */
        UNREADABLE,
    }
}

/**
 * Merges the PDFs at [uris] into [file], in the order given; [names] are their file names.
 * Protected ones are opened with the password [passwordFor] gives. A merge is all or nothing:
 * every file is checked first, and if one can't be merged, nothing is written and the failure
 * names it (unreadable files first, as no password helps them).
 */
suspend fun Context.mergePdfs(
    uris: List<Uri>,
    names: List<String>,
    file: File,
    passwordFor: suspend (Uri) -> String?,
): MergeFailure? = withContext(Dispatchers.IO) {
    val unreadable = mutableListOf<String>()
    val locked = mutableListOf<String>()
    val passwords = uris.mapIndexed { index, uri ->
        ensureActive()
        val needsPassword = runCatchingCancellable { readSource(uri) { PdfFile(it).check() } }.getOrNull()
        when (needsPassword) {
            null -> unreadable += names[index]
            true -> {
                val password = passwordFor(uri)
                val opens = password != null &&
                    runCatchingCancellable { readSource(uri) { PdfFile(it, password).check() } }.getOrNull() == false
                if (!opens) locked += names[index]
                return@mapIndexed password
            }
            false -> Unit
        }
        null
    }
    unreadable.failure(MergeFailure.Reason.UNREADABLE)?.let { return@withContext it }
    locked.failure(MergeFailure.Reason.NEEDS_PASSWORD)?.let { return@withContext it }

    val failure = file.outputStream().buffered().use { output ->
        val merger = PdfMerger(output)
        uris.forEachIndexed { index, uri ->
            ensureActive()
            // Damage the check didn't find, deeper in the file.
            val added = runCatchingCancellable { readSource(uri) { merger.add(it, passwords[index], names[index]) } }
            if (added.isFailure) return@use MergeFailure(MergeFailure.Reason.UNREADABLE, names[index], 0)
        }
        merger.finish(ZonedDateTime.now())
        null
    }
    if (failure != null) file.delete()
    failure
}

/** Whether the file needs a password it wasn't given; throws if it isn't a PDF that can be read. */
private fun PdfFile.check(): Boolean {
    if (isLocked) return true
    checkNotNull(root)
    return false
}

private fun List<String>.failure(reason: MergeFailure.Reason) =
    firstOrNull()?.let { MergeFailure(reason, it, size - 1) }
