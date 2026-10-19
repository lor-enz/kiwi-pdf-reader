package kiwi.lorenz.pdfreader.ui

import android.content.Context
import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.info.DocumentInfo
import kiwi.lorenz.pdfreader.info.PageSize
import kiwi.lorenz.pdfreader.info.heightMm
import kiwi.lorenz.pdfreader.info.paper
import kiwi.lorenz.pdfreader.info.widthMm
import java.text.NumberFormat
import java.time.Instant

/**
 * What the document says about itself, and its pages and size. [load] reads it from the file;
 * [pageCount] is used when known (0 if not), as the opened document knows best. The [title] is
 * the heading's, as in the other sheets.
 */
@Composable
fun InfoSheet(
    name: String,
    title: String?,
    pageCount: Int,
    load: suspend () -> DocumentInfo,
    onDismiss: () -> Unit,
) {
    // Shown once read (a few milliseconds): a sheet whose content grows later keeps its height.
    val info by produceState<DocumentInfo?>(null) { value = load() }
    val loaded = info ?: return
    ActionSheet(onDismiss) {
        Heading(name, title, TestTags.INFO_SHEET_TITLE)
        Details(loaded, pageCount)
    }
}

@Composable
private fun Details(info: DocumentInfo, pageCount: Int) {
    val context = LocalContext.current
    val metadata = info.metadata
    val pages = pageCount.takeIf { it > 0 } ?: metadata.pageCount
    val created = metadata.created?.let { formatDate(context, it) }
    // Most documents are never changed after they're made.
    val modified = metadata.modified?.let { formatDate(context, it) }?.takeUnless { it == created }

    SelectionContainer {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Lined up with the name in the heading.
            modifier = Modifier.padding(start = 56.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        ) {
            Detail(R.string.info_author, metadata.author, TestTags.INFO_AUTHOR)
            Detail(R.string.info_subject, metadata.subject, TestTags.INFO_SUBJECT)
            Detail(R.string.info_pages, pages?.toString(), TestTags.INFO_PAGES)
            Detail(R.string.info_page_size, metadata.pageSize?.let { formatPageSize(context, it) }, TestTags.INFO_PAGE_SIZE)
            Detail(R.string.info_file_size, info.sizeBytes?.let { Formatter.formatFileSize(context, it) }, TestTags.INFO_FILE_SIZE)
            Detail(R.string.info_created, created, TestTags.INFO_CREATED)
            Detail(R.string.info_modified, modified, TestTags.INFO_MODIFIED)
            // The app it was written in says more than the one that turned it into a PDF.
            Detail(R.string.info_made_with, metadata.creator ?: metadata.producer, TestTags.INFO_MADE_WITH)
        }
    }
}

/** Left out when there's no [value]. */
@Composable
private fun Detail(@StringRes label: Int, value: String?, tag: String) {
    value ?: return
    Column {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(tag),
        )
    }
}

private fun formatDate(context: Context, instant: Instant): String = DateUtils.formatDateTime(
    context,
    instant.toEpochMilli(),
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
)

/** "A4", "A4, landscape", or the measurements in the units people use where they live. */
private fun formatPageSize(context: Context, size: PageSize): String {
    size.paper()?.let { named ->
        return if (named.isLandscape) context.getString(R.string.page_size_landscape, named.paper.label) else named.paper.label
    }
    val number = NumberFormat.getNumberInstance()
    val isUs = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
    return if (isUs) {
        number.maximumFractionDigits = 1
        context.getString(R.string.page_size_inches, number.format(size.width / 72), number.format(size.height / 72))
    } else {
        number.maximumFractionDigits = 0
        context.getString(R.string.page_size_mm, number.format(size.widthMm), number.format(size.heightMm))
    }
}
