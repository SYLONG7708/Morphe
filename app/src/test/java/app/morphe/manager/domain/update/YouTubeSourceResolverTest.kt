package app.morphe.manager.domain.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeSourceResolverTest {
    @Test
    fun `resolves exact recommended version and descending build codes`() {
        val result = YouTubeSourceResolver.resolve(
            recommendedVersion = "21.04.223",
            selectedBundleUid = 7,
            compatibleVersions = listOf(
                YouTubeVersionBuild("21.04.223", 7, setOf(1561052631, 1561052632)),
                YouTubeVersionBuild("21.05.100", 7, setOf(1562000000)),
                YouTubeVersionBuild("21.04.223", 8, setOf(1561052699)),
            ),
        )

        assertEquals(listOf(1561052632, 1561052631), result.map { it.versionCode })
        assertEquals(
            "https://d.apkpure.net/b/APK/com.google.android.youtube?versionCode=1561052632",
            result.first().downloadUrl,
        )
    }

    @Test
    fun `deduplicates builds when bundle selection is not needed`() {
        val result = YouTubeSourceResolver.resolve(
            recommendedVersion = "21.04.223",
            selectedBundleUid = null,
            compatibleVersions = listOf(
                YouTubeVersionBuild("21.04.223", 7, setOf(1561052632)),
                YouTubeVersionBuild("21.04.223", 8, setOf(1561052632)),
            ),
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `refuses versions without exact build code`() {
        assertTrue(
            YouTubeSourceResolver.resolve(
                recommendedVersion = "21.04.223",
                selectedBundleUid = null,
                compatibleVersions = listOf(
                    YouTubeVersionBuild("21.04.223", 7, null),
                ),
            ).isEmpty()
        )
    }

    @Test
    fun `uses release-pinned exact source when bundle omits build codes`() {
        val result = YouTubeSourceResolver.resolveWithPinnedFallback(
            recommendedVersion = "21.04.223",
            selectedBundleUid = 7,
            compatibleVersions = listOf(
                YouTubeVersionBuild("21.04.223", 7, null),
            ),
            allowPinnedFallback = true,
            pinnedVersion = "21.04.223",
            pinnedVersionCode = 1561052632,
            pinnedSha256 = "78571be679f586d11a4e56fb1ce6bf9dfd958ce6b8af786c4a3bd94792ce8c7c",
        )

        assertEquals(1, result.size)
        assertEquals(1561052632, result.single().versionCode)
        assertEquals(
            "78571be679f586d11a4e56fb1ce6bf9dfd958ce6b8af786c4a3bd94792ce8c7c",
            result.single().sha256,
        )
    }

    @Test
    fun `rejects pinned fallback with mismatched version or invalid hash`() {
        val compatible = listOf(YouTubeVersionBuild("21.04.223", 7, null))

        assertTrue(
            YouTubeSourceResolver.resolveWithPinnedFallback(
                recommendedVersion = "21.04.223",
                selectedBundleUid = 7,
                compatibleVersions = compatible,
                allowPinnedFallback = true,
                pinnedVersion = "21.05.100",
                pinnedVersionCode = 1561052632,
                pinnedSha256 = "78571be679f586d11a4e56fb1ce6bf9dfd958ce6b8af786c4a3bd94792ce8c7c",
            ).isEmpty()
        )
        assertTrue(
            YouTubeSourceResolver.resolveWithPinnedFallback(
                recommendedVersion = "21.04.223",
                selectedBundleUid = 7,
                compatibleVersions = compatible,
                allowPinnedFallback = true,
                pinnedVersion = "21.04.223",
                pinnedVersionCode = 1561052632,
                pinnedSha256 = "not-a-sha256",
            ).isEmpty()
        )
    }
}
