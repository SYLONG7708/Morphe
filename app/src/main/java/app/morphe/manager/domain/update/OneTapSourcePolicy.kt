/*
 * SyMorphe one-tap source selection policy, 2026.
 */

package app.morphe.manager.domain.update

enum class OneTapYouTubeMode {
    LOCAL_INSTALLED,
    MORPHE_RECOMMENDED,
}

/**
 * Keeps the two user-facing one-tap choices distinct:
 * - local mode never downloads or substitutes a saved APK;
 * - recommended mode uses a local/saved source only when its version is the recommendation.
 */
object OneTapSourcePolicy {
    fun useInstalled(
        mode: OneTapYouTubeMode?,
        installedVersion: String?,
        recommendedVersion: String?,
    ): Boolean = installedVersion != null && when (mode) {
        OneTapYouTubeMode.LOCAL_INSTALLED -> true
        OneTapYouTubeMode.MORPHE_RECOMMENDED ->
            recommendedVersion != null && installedVersion == recommendedVersion
        null -> false
    }

    fun useSaved(
        mode: OneTapYouTubeMode?,
        savedVersion: String?,
        recommendedVersion: String?,
    ): Boolean =
        mode == OneTapYouTubeMode.MORPHE_RECOMMENDED &&
            savedVersion != null &&
            recommendedVersion != null &&
            savedVersion == recommendedVersion

    fun downloadRecommended(mode: OneTapYouTubeMode?): Boolean =
        mode == OneTapYouTubeMode.MORPHE_RECOMMENDED
}
