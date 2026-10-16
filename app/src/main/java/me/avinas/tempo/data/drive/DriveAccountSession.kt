package me.avinas.tempo.data.drive

import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Keep every request and retry in a history operation on its original account. */
internal class DriveAccountSession(private val currentEmail: () -> String?) {
    private class Binding(val email: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Binding>
    }

    suspend fun <T> withAccount(block: suspend (String) -> T): T {
        val email = normalizedEmail() ?: throw DriveException.Auth(
            "Google account identity is unavailable. Reconnect Google Drive."
        )
        return withContext(Binding(email)) { block(email) }
    }

    suspend fun requireUnchangedAccount() {
        val binding = coroutineContext[Binding] ?: return
        if (normalizedEmail() != binding.email) {
            throw DriveException.Auth(
                "Google account changed during history sync. Connect Google again before retrying."
            )
        }
    }

    private fun normalizedEmail(): String? = currentEmail()?.trim()?.lowercase()
        ?.takeIf { it.isNotEmpty() }
}
