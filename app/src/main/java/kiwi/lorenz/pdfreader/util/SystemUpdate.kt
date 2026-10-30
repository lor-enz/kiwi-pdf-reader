package kiwi.lorenz.pdfreader.util

import android.os.Build
import android.os.ext.SdkExtensions

/**
 * True on Android 14 without the Google Play system update (SDK extension 13) that `androidx.pdf`
 * needs. Pages still show, but links, search, forms and protected PDFs don't work.
 */
val isSystemUpdateMissing: Boolean = SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < 13
