package kiwi.lorenz.pdfreader.util

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A short message for a screen's snackbar (a string resource), cleared once shown. */
class SnackbarMessage {
    var current by mutableStateOf<Int?>(null)
        private set

    fun show(@StringRes message: Int) {
        current = message
    }

    fun shown() {
        current = null
    }
}
