package kiwi.lorenz.pdfreader.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but rethrows [CancellationException], so a cancelled coroutine still stops,
 * and catches only exceptions, not errors such as running out of memory.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
