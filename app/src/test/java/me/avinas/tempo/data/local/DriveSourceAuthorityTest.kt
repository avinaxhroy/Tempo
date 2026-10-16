package me.avinas.tempo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveSourceAuthorityTest {

    @Test
    fun `Drive transport preserves the original source authority`() {
        assertEquals(
            SourceAuthority.rank("import.json.spotify"),
            SourceAuthority.rank("drive:remote-device:import.json.spotify")
        )
        assertEquals(
            SourceAuthority.rank("desktop:windows"),
            SourceAuthority.rank("drive:remote-device:desktop:windows")
        )
        assertEquals(
            SourceAuthority.rank("desktop:Spotify"),
            SourceAuthority.rank("lan:desktop-device:desktop:Spotify")
        )
        assertEquals(
            SourceAuthority.rank("browser:Spotify"),
            SourceAuthority.rank("drive:android-relay:browser:Spotify")
        )
        assertEquals(100, SourceAuthority.rank("drive:remote-device"))
        assertEquals(100, SourceAuthority.rank("drive::android"))
    }

    @Test
    fun `Drive device id is extracted only from a complete transport source`() {
        assertEquals(
            "remote-device",
            SourceAuthority.driveDeviceId("drive:remote-device:android")
        )
        assertEquals(
            "desktop-device",
            SourceAuthority.driveDeviceId("lan:desktop-device:desktop:Spotify")
        )
        assertNull(SourceAuthority.driveDeviceId("android"))
        assertNull(SourceAuthority.driveDeviceId("drive::android"))
        assertNull(SourceAuthority.driveDeviceId("drive:remote-device"))
    }
}
