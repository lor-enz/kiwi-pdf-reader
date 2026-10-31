package kiwi.lorenz.pdfreader.settings

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kiwi.lorenz.pdfreader.R
import kiwi.lorenz.pdfreader.defaultapp.DefaultHint
import kiwi.lorenz.pdfreader.defaultapp.PdfDefault
import kiwi.lorenz.pdfreader.recent.CleanupLimits
import kiwi.lorenz.pdfreader.recent.KeepUnused
import kiwi.lorenz.pdfreader.recent.RecentFile
import kiwi.lorenz.pdfreader.recent.StorageLimit
import kiwi.lorenz.pdfreader.ui.ConfirmDialog
import kiwi.lorenz.pdfreader.ui.ContentMaxWidth
import kiwi.lorenz.pdfreader.ui.MessageEffect
import kiwi.lorenz.pdfreader.ui.TestTags
import kiwi.lorenz.pdfreader.ui.theme.PdfPenguinTheme
import kotlin.math.roundToInt

/** What the app keeps on the phone, for how long, and where to learn more about it. */
@Composable
fun SettingsScreen(
    recentFiles: List<RecentFile>,
    cleanupLimits: CleanupLimits,
    pendingLimits: PendingLimits?,
    onChooseLimits: (CleanupLimits) -> Unit,
    onUndoLimits: (PendingLimits) -> Unit,
    onApplyLimits: (PendingLimits) -> Unit,
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
    val removedMessage = pendingLimits?.let {
        if (it.removedEntries > 0) {
            pluralStringResource(R.plurals.limits_removed_entries, it.removedEntries, it.removedEntries)
        } else {
            pluralStringResource(R.plurals.limits_dropped_filled, it.droppedFilled, it.droppedFilled)
        }
    }
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(pendingLimits) {
        val pending = pendingLimits ?: return@LaunchedEffect
        var isUndone = false
        try {
            // With an action, the default duration would be indefinite.
            val result = snackbarHostState.showSnackbar(removedMessage!!, undoLabel, duration = SnackbarDuration.Long)
            isUndone = result == SnackbarResult.ActionPerformed
        } finally {
            // Also when leaving the screen: files are only deleted once undo is impossible.
            if (isUndone) onUndoLimits(pending) else onApplyLimits(pending)
        }
    }
    // Waits for an undo snackbar instead of dismissing it, which would end the undo.
    MessageEffect(message, onMessageShown, snackbarHostState)

    SubScreen(R.string.settings, onBack, snackbarHostState) {
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

        SectionHeader(R.string.stored_on_device)
        // The pending choice shows until it's undone.
        val limits = pendingLimits?.limits ?: cleanupLimits
        SettingsRow(R.drawable.ic_history, R.string.keep_unused, stringResource(R.string.keep_unused_text))
        StopSlider(
            options = KeepUnused.entries,
            selected = limits.keepUnused,
            label = { it.label },
            onSelect = { onChooseLimits(limits.copy(keepUnused = it)) },
            tag = TestTags.KEEP_UNUSED,
        )
        SettingsRow(R.drawable.ic_storage, R.string.storage_limit, stringResource(R.string.storage_limit_text))
        StopSlider(
            options = StorageLimit.entries,
            selected = limits.storageLimit,
            label = { it.label },
            onSelect = { onChooseLimits(limits.copy(storageLimit = it)) },
            tag = TestTags.STORAGE_LIMIT,
        )
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
        // Scrolls across the whole width, also beside the content.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(PaddingValues(bottom = 16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
                content()
            }
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

/** A row that only describes, like the heading of a [StopSlider]. */
@Composable
private fun SettingsRow(@DrawableRes icon: Int, @StringRes label: Int, summary: String) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * Picks one of a few [options] in order, like a switch with more than two positions: the thumb
 * snaps to a stop, and the choice is made when it's let go. The labels below can be tapped too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> StopSlider(
    options: List<T>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit,
    tag: String,
) {
    val selectedIndex = options.indexOf(selected)
    // The stop the thumb is headed for; the thumb slides there instead of jumping.
    var target by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
    val state = remember {
        SliderState(selectedIndex.toFloat(), steps = options.size - 2, valueRange = 0f..options.lastIndex.toFloat())
            // Would snap every value of the slide to a stop, so the thumb would still jump.
            .apply { shouldAutoSnap = false }
    }
    LaunchedEffect(target) {
        animate(state.value, target.toFloat(), animationSpec = tween(STOP_SLIDE_MILLIS)) { value, _ ->
            state.value = value
        }
    }
    val view = LocalView.current
    fun moveTo(index: Int) {
        if (index != target) view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        target = index
    }
    // Drags and taps report the stop they're at, which the thumb then slides to.
    state.onValueChange = { moveTo(it.roundToInt()) }
    state.onValueChangeFinished = { onSelect(options[target]) }
    val stateDescription = stringResource(label(options[target]))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Slider(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.stateDescription = stateDescription }
                .testTag(tag),
        )
        StopLabels(count = options.size, selected = target, thumb = { state.value }) { index, form ->
            val isSelected = index == target
            val text = stringResource(label(options[index]))
            Text(
                // "3 months" as "3" above "months".
                text = if (form == LabelForm.STACKED) text.replaceFirst(' ', '\n') else text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                softWrap = false,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        moveTo(index)
                        onSelect(options[index])
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag(TestTags.SLIDER_STOP),
            )
        }
    }
}

/** How a [StopSlider]'s labels are shown, the first form whose labels don't overlap. */
private enum class LabelForm {
    /** Each label on one line. */
    LINE,

    /** The number above the unit, for large fonts. */
    STACKED,

    /** Only the selected stop's label, following the thumb, when even stacked labels overlap. */
    SELECTED,
}

/**
 * Lays out a [StopSlider]'s labels: the ends at the track's ends, the others centered below their
 * stop. [thumb] is the thumb's position in stops, which the label of the [SELECTED][LabelForm.SELECTED]
 * form follows.
 */
@Composable
private fun StopLabels(
    count: Int,
    selected: Int,
    thumb: () -> Float,
    label: @Composable (index: Int, form: LabelForm) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun x(stop: Float, labelWidth: Int) = ((width - labelWidth) * stop / (count - 1)).roundToInt()
        fun measure(form: LabelForm) =
            subcompose(form) { repeat(count) { label(it, form) } }.map { it.measure(loose) }
        fun fits(labels: List<Placeable>) = (0 until count - 1).all {
            x(it.toFloat(), labels[it].width) + labels[it].width <= x(it + 1f, labels[it + 1].width)
        }
        val all = measure(LabelForm.LINE).takeIf(::fits) ?: measure(LabelForm.STACKED).takeIf(::fits)
        if (all != null) {
            val height = all.maxOf { it.height }
            layout(width, height) {
                all.forEachIndexed { index, it -> it.place(x(index.toFloat(), it.width), (height - it.height) / 2) }
            }
        } else {
            val one = subcompose(LabelForm.SELECTED) { label(selected, LabelForm.SELECTED) }.single().measure(loose)
            layout(width, one.height) { one.place(x(thumb(), one.width), 0) }
        }
    }
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

private val KeepUnused.label
    get() = when (this) {
        KeepUnused.WEEK -> R.string.keep_week
        KeepUnused.THREE_MONTHS -> R.string.keep_three_months
        KeepUnused.YEAR -> R.string.keep_year
    }

private val StorageLimit.label
    get() = when (this) {
        StorageLimit.MB_100 -> R.string.storage_100_mb
        StorageLimit.MB_500 -> R.string.storage_500_mb
        StorageLimit.GB_2 -> R.string.storage_2_gb
    }

/** How long a [StopSlider]'s thumb takes from one stop to the next. */
private const val STOP_SLIDE_MILLIS = 200

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
            cleanupLimits = CleanupLimits(),
            pendingLimits = null,
            onChooseLimits = {},
            onUndoLimits = {},
            onApplyLimits = {},
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
