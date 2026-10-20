package kiwi.lorenz.pdfreader.ui

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.PdfDefault
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.ui.theme.PdfPenguinTheme

/** What the app keeps on the phone, and where to learn more about it. No preferences (yet). */
@Composable
fun SettingsScreen(
    recentFiles: List<RecentFile>,
    versionName: String,
    pdfDefault: PdfDefault,
    onMakeDefault: () -> Unit,
    onChangeDefault: (PdfDefault.OtherApp) -> Unit,
    onBack: () -> Unit,
    onForgetAllPasswords: () -> Unit,
    onClearRecentFiles: () -> Unit,
    onCopyVersion: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onReportProblem: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenLicenses: () -> Unit,
    message: Int?,
    onMessageShown: () -> Unit,
) {
    var isForgetDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isClearDialogOpen by rememberSaveable { mutableStateOf(false) }
    if (isForgetDialogOpen) {
        ConfirmDialog(
            title = R.string.forget_all_passwords_title,
            text = R.string.forget_all_passwords_text,
            confirm = R.string.forget,
            confirmTag = TestTags.CONFIRM_FORGET_ALL_PASSWORDS,
            onConfirm = { isForgetDialogOpen = false; onForgetAllPasswords() },
            onDismiss = { isForgetDialogOpen = false },
        )
    }
    if (isClearDialogOpen) {
        ConfirmDialog(
            title = R.string.clear_recent_files_title,
            text = R.string.clear_recent_files_text,
            confirm = R.string.clear,
            confirmTag = TestTags.CONFIRM_CLEAR_RECENT_FILES,
            onConfirm = { isClearDialogOpen = false; onClearRecentFiles() },
            onDismiss = { isClearDialogOpen = false },
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    MessageEffect(message, onMessageShown, snackbarHostState)

    SubScreen(R.string.settings, onBack, snackbarHostState) {
        SectionHeader(R.string.stored_on_phone)
        val passwords = recentFiles.count { it.encryptedPassword != null }
        SettingsRow(
            icon = R.drawable.ic_key_off,
            label = R.string.forget_all_passwords,
            summary = if (passwords == 0) {
                stringResource(R.string.no_passwords_remembered)
            } else {
                pluralStringResource(R.plurals.passwords_remembered, passwords, passwords)
            },
            tag = TestTags.FORGET_ALL_PASSWORDS,
            enabled = passwords > 0,
            onClick = { isForgetDialogOpen = true },
        )
        val storedBytes = recentFiles.sumOf { it.storedBytes }
        SettingsRow(
            icon = R.drawable.ic_delete_sweep,
            label = R.string.clear_recent_files,
            summary = if (recentFiles.isEmpty()) {
                stringResource(R.string.no_recent_files)
            } else {
                val size = Formatter.formatShortFileSize(LocalContext.current, storedBytes)
                pluralStringResource(R.plurals.recent_files_stored, recentFiles.size, recentFiles.size, size)
            },
            tag = TestTags.CLEAR_RECENT_FILES,
            enabled = recentFiles.isNotEmpty(),
            onClick = { isClearDialogOpen = true },
        )
        // Can't be dismissed here: this is where it's always found.
        DefaultHint(
            pdfDefault = pdfDefault,
            isVisible = pdfDefault != PdfDefault.ThisApp,
            onMakeDefault = onMakeDefault,
            onChangeDefault = onChangeDefault,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        AnimatedVisibility(visible = pdfDefault == PdfDefault.ThisApp) {
            Text(
                text = stringResource(R.string.is_default_pdf_app),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(TestTags.IS_DEFAULT),
            )
        }

        SectionHeader(R.string.about)
        SettingsRow(R.drawable.ic_info, R.string.version, versionName, TestTags.VERSION, onClick = onCopyVersion)
        SettingsRow(
            R.drawable.ic_code, R.string.source_code, stringResource(R.string.source_code_summary),
            TestTags.SOURCE_CODE, onClick = onOpenSourceCode,
        )
        SettingsRow(
            R.drawable.ic_bug_report, R.string.report_problem, stringResource(R.string.report_problem_summary),
            TestTags.REPORT_PROBLEM, onClick = onReportProblem,
        )
        SettingsRow(
            R.drawable.ic_privacy_tip, R.string.privacy_policy, stringResource(R.string.privacy_policy_summary),
            TestTags.PRIVACY_POLICY, onClick = onOpenPrivacyPolicy,
        )
        SettingsRow(R.drawable.ic_description, R.string.licenses, null, TestTags.LICENSES, onClick = onOpenLicenses)
    }
}

/** The open source components the app ships with, and the license they share. */
@Composable
fun LicensesScreen(components: List<String>, licenseText: String, onBack: () -> Unit) {
    SubScreen(R.string.licenses, onBack) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.licenses_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = components.joinToString("\n") { "• $it" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Text(
                text = licenseText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(TestTags.LICENSE_TEXT),
            )
        }
    }
}

/** A scrolling page below a top bar with a back button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreen(
    @StringRes title: Int,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(TestTags.BACK)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                // Like the page until content scrolls under it (surface differs when dark).
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(PaddingValues(bottom = 16.dp)),
        ) {
            content()
        }
    }
}

/** Styled like the recent files list's heading. */
@Composable
private fun SectionHeader(@StringRes text: Int) {
    Text(
        text = stringResource(text).uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    summary: String?,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val content = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = content,
            supportingColor = variant,
            leadingIconColor = variant,
        ),
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag),
    )
}

/** Material's opacity for disabled content. */
private const val DISABLED_ALPHA = 0.38f

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    PdfPenguinTheme {
        SettingsScreen(
            recentFiles = listOf(
                RecentFile("1", "", "a.pdf", true, sizeBytes = 2_400_000, lastOpenedMillis = 0, encryptedPassword = "x"),
                RecentFile("2", "", "b.pdf", false, lastOpenedMillis = 0),
            ),
            versionName = "1.5",
            pdfDefault = PdfDefault.None,
            onMakeDefault = {},
            onChangeDefault = {},
            onBack = {},
            onForgetAllPasswords = {},
            onClearRecentFiles = {},
            onCopyVersion = {},
            onOpenSourceCode = {},
            onReportProblem = {},
            onOpenPrivacyPolicy = {},
            onOpenLicenses = {},
            message = null,
            onMessageShown = {},
        )
    }
}
