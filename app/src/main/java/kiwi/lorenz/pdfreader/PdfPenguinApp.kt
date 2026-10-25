package kiwi.lorenz.pdfreader

import android.app.Application
import android.os.Process
import kiwi.lorenz.pdfreader.home.LauncherShortcuts
import kiwi.lorenz.pdfreader.recent.RecentFilesStore

/** Keeps the launcher shortcuts in step with the recent files list for as long as the process lives. */
class PdfPenguinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // androidx.pdf renders in an isolated process of its own, which can't reach the app's
        // files or shared preferences: the store would crash it, and no document would open.
        if (Process.isIsolated()) return
        LauncherShortcuts(this, RecentFilesStore.get(this)).start()
    }
}
