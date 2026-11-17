package me.avinas.tempo.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only work that is genuinely in flight may block a new Drive action. A Success or
 * Error parked on screen while its snackbar is shown used to swallow the next tap on
 * Sign out / Connect / Backup Now, which reads as "the button does nothing" right
 * after the user tried to sign in again.
 */
class DriveOperationStateTest {

    @Test
    fun `idle and finished states do not block a new operation`() {
        assertFalse(DriveOperationState.Idle.isBusy)
        assertFalse(DriveOperationState.Success("Backup uploaded successfully").isBusy)
        assertFalse(DriveOperationState.Error("Session expired. Please sign in again.").isBusy)
    }

    @Test
    fun `running states block a second operation`() {
        assertTrue(DriveOperationState.SigningIn.isBusy)
        assertTrue(DriveOperationState.Loading.isBusy)
        assertTrue(DriveOperationState.Uploading(0.5f).isBusy)
        assertTrue(DriveOperationState.Downloading(0.5f).isBusy)
        assertTrue(DriveOperationState.Restoring.isBusy)
    }
}
