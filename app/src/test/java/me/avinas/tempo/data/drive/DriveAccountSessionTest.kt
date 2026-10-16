package me.avinas.tempo.data.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAccountSessionTest {
    @Test
    fun `account change after suspension prevents further requests`() = runTest {
        var email: String? = "first@example.com"
        val session = DriveAccountSession { email }
        var requests = 0
        session.withAccount { original ->
            assertEquals("first@example.com", original)
            session.requireUnchangedAccount()
            requests++
            email = "second@example.com"
            val failure = withContext(Dispatchers.Default) {
                runCatching { session.requireUnchangedAccount(); requests++ }.exceptionOrNull()
            }
            assertTrue(failure is DriveException.Auth)
        }
        assertEquals(1, requests)
    }

    @Test
    fun `sign out invalidates the active operation and blank identity fails closed`() = runTest {
        var email: String? = "first@example.com"
        val session = DriveAccountSession { email }
        session.withAccount {
            email = null
            assertTrue(runCatching { session.requireUnchangedAccount() }.exceptionOrNull() is DriveException.Auth)
        }
        email = " "
        var entered = false
        val failure = runCatching { session.withAccount { entered = true } }.exceptionOrNull()
        assertTrue(failure is DriveException.Auth)
        assertTrue(!entered)
    }

    @Test
    fun `email case normalization and ordinary token refresh preserve the binding`() = runTest {
        var email: String? = " FIRST@example.com "
        val session = DriveAccountSession { email }
        session.withAccount { bound ->
            assertEquals("first@example.com", bound)
            email = "first@example.com"
            withContext(Dispatchers.Default) { session.requireUnchangedAccount() }
        }
    }
}
