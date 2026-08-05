package app.morphe.manager.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionUtilsTest {
    @Test
    fun `multi digit patch does not outrank next minor version`() {
        assertTrue(compareVersions("1.1.0", "1.0.22") > 0)
        assertFalse(isNewerVersion("1.1.0", "1.0.22"))
    }

    @Test
    fun `newer minor version remains an update`() {
        assertTrue(isNewerVersion("1.0.22", "1.1.0"))
    }

    @Test
    fun `stable release outranks prerelease with same core`() {
        assertTrue(compareVersions("1.1.0", "1.1.0-beta.9") > 0)
        assertFalse(isNewerVersion("1.1.0", "1.1.0-beta.9"))
    }
}
