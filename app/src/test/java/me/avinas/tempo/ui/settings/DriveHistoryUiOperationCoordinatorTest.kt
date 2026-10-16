package me.avinas.tempo.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator survives ViewModel recreation while application-scope Drive work runs. */
class DriveHistoryUiOperationCoordinatorTest {
    @Test
    fun `progress and result remain visible across screen recreation`() {
        val coordinator = DriveHistoryUiOperationCoordinator()
        val originalView = coordinator.operation
        assertEquals(DriveHistoryUiOperation.Idle, originalView.value)
        assertTrue(coordinator.begin())
        assertEquals(DriveHistoryUiOperation.Running, originalView.value)

        // A recreated ViewModel observes the same shared coordinator.
        val recreatedView = coordinator.operation
        assertEquals(DriveHistoryUiOperation.Running, recreatedView.value)
        assertFalse(coordinator.begin())
        coordinator.clear()
        assertEquals(DriveHistoryUiOperation.Running, recreatedView.value)

        coordinator.finish(DriveHistoryUiOperation.Success("History synchronized"))
        assertEquals(
            DriveHistoryUiOperation.Success("History synchronized"),
            recreatedView.value
        )
        coordinator.clear()
        assertEquals(DriveHistoryUiOperation.Idle, originalView.value)
        assertTrue(coordinator.begin())
    }
}
