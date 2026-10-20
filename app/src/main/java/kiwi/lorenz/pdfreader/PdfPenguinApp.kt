package kiwi.lorenz.pdfreader

import android.app.Application
import kiwi.lorenz.pdfreader.home.LauncherShortcuts
import kiwi.lorenz.pdfreader.recent.RecentFilesStore

/** Keeps the launcher shortcuts in step with the recent files list for as long as the process lives. */
class PdfPenguinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LauncherShortcuts(this, RecentFilesStore.get(this)).start()
    }
}
