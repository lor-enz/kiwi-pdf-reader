package kiwi.lorenz.pdfreader.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.ui.theme.KiwiPDFReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun HomeScreen(
    recentFiles: List<RecentFile>,
    thumbnailFile: (RecentFile) -> File,
    onOpenPdf: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
) {
    Scaffold { innerPadding ->
        if (recentFiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(onOpenPdf)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 32.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Header(onOpenPdf) }
                item {
                    Text(
                        text = stringResource(R.string.recent_files).uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 4.dp, start = 4.dp),
                    )
                }
                items(recentFiles, key = { it.id }) { entry ->
                    RecentFileRow(
                        entry = entry,
                        thumbnailFile = thumbnailFile,
                        onClick = { onOpenRecent(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onOpenPdf: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onOpenPdf,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.testTag(TestTags.OPEN_PDF),
        ) {
            Text(stringResource(R.string.open_pdf))
        }
    }
}

@Composable
private fun RecentFileRow(
    entry: RecentFile,
    thumbnailFile: (RecentFile) -> File,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .testTag(TestTags.RECENT_FILE)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry, thumbnailFile)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = details(entry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Thumbnail(entry: RecentFile, thumbnailFile: (RecentFile) -> File) {
    val bitmap by produceState<ImageBitmap?>(null, entry.id, entry.hasThumbnail) {
        if (entry.hasThumbnail) {
            value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(thumbnailFile(entry).path)?.asImageBitmap()
            }
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "PDF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun details(entry: RecentFile): String {
    val now = System.currentTimeMillis()
    val opened = if (now - entry.lastOpenedMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            entry.lastOpenedMillis, now, DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }
    if (entry.pageCount == 0) return opened
    val page = stringResource(R.string.page_of, entry.lastPage + 1, entry.pageCount)
    return stringResource(R.string.recent_details, page, opened)
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    KiwiPDFReaderTheme {
        HomeScreen(recentFiles = emptyList(), thumbnailFile = { File("") }, onOpenPdf = {}, onOpenRecent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenRecentPreview() {
    val now = System.currentTimeMillis()
    KiwiPDFReaderTheme {
        HomeScreen(
            recentFiles = listOf(
                RecentFile("1", "", "Electricity bill March.pdf", false, lastOpenedMillis = now, pageCount = 3),
                RecentFile("2", "", "Manual.pdf", true, lastOpenedMillis = now - 86_400_000, pageCount = 28, lastPage = 11),
            ),
            thumbnailFile = { File("") },
            onOpenPdf = {},
            onOpenRecent = {},
        )
    }
}
