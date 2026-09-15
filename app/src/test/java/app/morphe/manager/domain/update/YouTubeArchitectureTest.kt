package app.morphe.manager.domain.update

import app.morphe.manager.domain.bundles.extractAppTargets
import app.morphe.manager.patcher.patch.PatchBundleInfo
import app.morphe.manager.patcher.patch.PatchInfo
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.SupportedAbi
import app.morphe.patcher.patch.bytecodePatch
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeArchitectureTest {
    private val target = YouTubeVersionBuild(
        "21.07.247", 0, setOf(101, 102, 103),
        mapOf("armeabi-v7a" to 101, "arm64-v8a" to 102, "x86_64" to 103),
    )

    @Test fun `32 bit Android selects ARM32 even on a 64 bit capable CPU`() {
        val result = YouTubeSourceResolver.resolve("21.07.247", 0, listOf(target), listOf("armeabi-v7a", "armeabi"))
        assertEquals(listOf(101), result.map { it.versionCode })
    }

    @Test fun `64 bit Android prefers ARM64 with compatible ARM32 fallback`() {
        val result = YouTubeSourceResolver.resolve("21.07.247", 0, listOf(target), listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals(listOf(102, 101), result.map { it.versionCode })
    }

    @Test fun `64 bit only Android never downloads ARM32`() {
        assertEquals(listOf(102), YouTubeSourceResolver.resolve(
            "21.07.247", 0, listOf(target), listOf("arm64-v8a"),
        ).map { it.versionCode })
    }

    @Test fun `incompatible source cannot fall back to pinned ARM64`() {
        assertTrue(YouTubeSourceResolver.resolveWithPinnedFallback(
            "21.07.247", 0, listOf(target), true, "21.07.247", 102, "a".repeat(64), listOf("x86"),
        ).isEmpty())
    }

    @Test fun `APK native libraries must match Android supported ABIs`() {
        assertFalse(sourceAbiCompatible(listOf("arm64-v8a"), listOf("armeabi-v7a")))
        assertFalse(sourceAbiCompatible(listOf("armeabi-v7a"), listOf("arm64-v8a")))
        assertTrue(sourceAbiCompatible(listOf("arm64-v8a", "armeabi-v7a"), listOf("armeabi-v7a")))
        assertTrue(sourceAbiCompatible(emptyList(), listOf("x86_64")))
    }

    @Test fun `x86 64 name stays compatible with Android spelling`() {
        assertEquals("x86_64", YouTubeSourceResolver.abiName("X86_64"))
    }

    @Test fun `bundle metadata retains ABI mapping through the catalog and download selection`() {
        val patch = bytecodePatch(name = "Architecture fixture") {
            compatibleWith(Compatibility(
                packageName = YouTubeSourceResolver.YOUTUBE_PACKAGE,
                name = "YouTube",
                targets = listOf(AppTarget(
                    version = "21.07.247",
                    versionCodes = mapOf(SupportedAbi.ARMEABI_V7A to 101, SupportedAbi.ARM64_V8A to 102),
                )),
            ))
        }
        val bundle = PatchBundleInfo.Global("Fixture", "1.0", 0, true, listOf(PatchInfo(patch)))
        val entries = extractAppTargets(mapOf(0 to bundle), mapOf(0 to "Fixture"))
            .getValue(YouTubeSourceResolver.YOUTUBE_PACKAGE)
        val candidates = entries.map { entry ->
            YouTubeVersionBuild(entry.target.version, entry.bundleUid, entry.buildCodes,
                entry.target.versionCodes.orEmpty().mapKeys { YouTubeSourceResolver.abiName(it.key.name) })
        }
        assertEquals(listOf(101), YouTubeSourceResolver.resolve(
            "21.07.247", 0, candidates, listOf("armeabi-v7a", "armeabi"),
        ).map { it.versionCode })
        assertEquals(listOf(102), YouTubeSourceResolver.resolve(
            "21.07.247", 0, candidates, listOf("arm64-v8a"),
        ).map { it.versionCode })
    }

    @Test fun `new official versions without build codes can discover a candidate`() {
        assertTrue(YouTubeSourceResolver.mayDiscoverBuildCodes(
            "21.13.164", 0, listOf(YouTubeVersionBuild("21.13.164", 0, null)),
        ))
        assertFalse(YouTubeSourceResolver.mayDiscoverBuildCodes("21.13.164", 0, listOf(target)))
        assertFalse(YouTubeSourceResolver.mayDiscoverBuildCodes("21.07.247", 0, listOf(target)))
        assertFalse(YouTubeSourceResolver.mayDiscoverBuildCodes("21.13.164", 1,
            listOf(YouTubeVersionBuild("21.13.164", 0, null))))
    }

    @Test fun `mirror discovery binds the version and APK link before independent signature verification`() {
        val page = """
            <a href="https://d.apkpure.net/b/APK/com.google.android.youtube?versionCode=1561063732"
               title="Download YouTube v21.13.164 for android" class="download" data-dt-version="21.13.164">Download</a>
            <a href="https://d.apkpure.net/b/APK/com.google.android.youtube?versionCode=999" data-dt-version="21.37.42">Newer</a>
            <a href="https://d.apkpure.net/b/XAPK/com.google.android.youtube?versionCode=100" data-dt-version="21.13.164">Split</a>
            <a href="https://evil.example/b/APK/com.google.android.youtube?versionCode=100" data-dt-version="21.13.164">Foreign</a>
            <a href="https://d.apkpure.net/b/APK/com.other.app?versionCode=100" data-dt-version="21.13.164">Other</a>
        """.trimIndent()
        val candidates = YouTubeSourceResolver.candidatesFromPage("21.13.164", page)
        assertEquals(listOf(1561063732), candidates.map { it.versionCode })
        assertEquals("21.13.164", candidates.single().version)
        assertEquals(YouTubeSourceResolver.YOUTUBE_PACKAGE, candidates.single().packageName)
    }
}
