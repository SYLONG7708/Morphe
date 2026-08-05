package app.morphe.manager.domain.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneTapSourcePolicyTest {
    @Test
    fun `local mode always uses the compatible installed source`() {
        assertTrue(
            OneTapSourcePolicy.useInstalled(
                mode = OneTapYouTubeMode.LOCAL_INSTALLED,
                installedVersion = "21.29.366",
                recommendedVersion = "21.04.223",
            )
        )
        assertFalse(OneTapSourcePolicy.downloadRecommended(OneTapYouTubeMode.LOCAL_INSTALLED))
    }

    @Test
    fun `recommended mode reuses only an exact installed or saved recommendation`() {
        assertFalse(
            OneTapSourcePolicy.useInstalled(
                mode = OneTapYouTubeMode.MORPHE_RECOMMENDED,
                installedVersion = "21.29.366",
                recommendedVersion = "21.04.223",
            )
        )
        assertTrue(
            OneTapSourcePolicy.useSaved(
                mode = OneTapYouTubeMode.MORPHE_RECOMMENDED,
                savedVersion = "21.04.223",
                recommendedVersion = "21.04.223",
            )
        )
        assertTrue(OneTapSourcePolicy.downloadRecommended(OneTapYouTubeMode.MORPHE_RECOMMENDED))
    }

    @Test
    fun `manual flow never enters an automatic source path`() {
        assertFalse(OneTapSourcePolicy.useInstalled(null, "21.04.223", "21.04.223"))
        assertFalse(OneTapSourcePolicy.useSaved(null, "21.04.223", "21.04.223"))
        assertFalse(OneTapSourcePolicy.downloadRecommended(null))
    }
}
