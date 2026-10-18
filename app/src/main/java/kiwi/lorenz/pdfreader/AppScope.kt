package kiwi.lorenz.pdfreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * For work that must finish after the screen that started it is gone, like storing a form's
 * last edits when its viewer closes. Lives as long as the process.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)
