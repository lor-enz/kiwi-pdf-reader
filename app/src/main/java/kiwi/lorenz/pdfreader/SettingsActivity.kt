package kiwi.lorenz.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kiwi.lorenz.pdfreader.defaultapp.openDefaultSettings
import kiwi.lorenz.pdfreader.document.openLink
import kiwi.lorenz.pdfreader.settings.LicensesScreen
import kiwi.lorenz.pdfreader.settings.SettingsScreen
import kiwi.lorenz.pdfreader.settings.SettingsViewModel
import kiwi.lorenz.pdfreader.ui.setAppContent

/** Settings and information about the app, opened from the home screen. */
class SettingsActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    private val versionName by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.pdfDefault.observe(this)

        setAppContent {
            val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
            val cleanupLimits by viewModel.cleanupLimits.collectAsStateWithLifecycle()
            SettingsScreen(
                recentFiles = recentFiles,
                cleanupLimits = cleanupLimits,
                pendingLimits = viewModel.pendingLimits,
                onChooseLimits = viewModel::chooseLimits,
                onUndoLimits = viewModel::undoLimits,
                onApplyLimits = viewModel::applyLimits,
                versionName = versionName,
                pdfDefault = viewModel.pdfDefault.pdfDefault,
                onMakeDefault = { viewModel.pdfDefault.ask(this) },
                onChangeDefault = { openDefaultSettings(it.packageName) },
                onBack = ::finish,
                onForgetAllPasswords = viewModel::forgetAllPasswords,
                onClearRecentFiles = viewModel::clearRecentFiles,
                onCopyVersion = ::copyVersion,
                onOpenSourceCode = { openPage(SOURCE_URL) },
                onReportProblem = { openPage(ISSUES_URL) },
                onOpenPrivacyPolicy = { openPage(PRIVACY_POLICY_URL) },
                onOpenLicenses = { startActivity(Intent(this, LicensesActivity::class.java)) },
                message = viewModel.message.current,
                onMessageShown = viewModel.message::shown,
            )
        }
    }

    /** For bug reports. Android shows its own confirmation, so there's no snackbar. */
    private fun copyVersion() {
        val text = "${getString(R.string.app_name)} $versionName · Android ${Build.VERSION.RELEASE} · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.version), text))
    }

    private fun openPage(url: String) {
        if (!openLink(url.toUri())) viewModel.message.show(R.string.link_failed)
    }

    private companion object {
        const val SOURCE_URL = "https://github.com/lor-enz/pdf-penguin-android"
        const val ISSUES_URL = "$SOURCE_URL/issues"
        const val PRIVACY_POLICY_URL = "https://lor-enz.github.io/pdf-penguin-android/privacy-policy"
    }
}

/** The licenses of the open source components, see [LicensesScreen]. */
class LicensesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setAppContent {
            val licenseText = remember {
                // Its lines are wrapped for 80 columns; rewrapped to the screen's width instead.
                resources.openRawResource(R.raw.apache_license).bufferedReader().use { it.readText() }
                    .split(Regex("""\n\s*\n"""))
                    .joinToString("\n\n") { it.trim().replace(Regex("""\s+"""), " ") }
            }
            LicensesScreen(COMPONENTS, licenseText, onBack = ::finish)
        }
    }

    private companion object {
        /** Everything in the release build's runtime classpath, all under the Apache License 2.0. */
        val COMPONENTS = listOf(
            "Android Jetpack (AndroidX, Jetpack Compose, PDF)",
            "Compose Multiplatform",
            "Telephoto (zoomable)",
            "Poko",
            "Material Components for Android",
            "Material Icons",
            "Kotlin and kotlinx.coroutines",
            "Guava (ListenableFuture)",
            "JSpecify",
        )
    }
}
