package app.morphe.manager.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SafeIntegrationProfileTest {
    @Test
    fun configuredPackagesShareOneVendorGroup() {
        assertEquals(
            "${SafeIntegrationProfile.vendorGroup}.android.youtube",
            SafeIntegrationProfile.patchedYouTubePackage,
        )
        assertEquals(
            "${SafeIntegrationProfile.vendorGroup}.android.gms",
            SafeIntegrationProfile.microgPackage,
        )
        assertEquals(
            SafeIntegrationProfile.microgPackage,
            SafeIntegrationProfile.compatiblePackagePairs[
                SafeIntegrationProfile.patchedYouTubePackage
            ],
        )
        assertEquals(
            "com.sylong.autopatch.aio7870test.android.gms",
            SafeIntegrationProfile.compatiblePackagePairs[
                "com.sylong.autopatch.aio7870test.android.youtube"
            ],
        )
    }

    @Test
    fun injectsSafePackageOnlyIntoBundlesUsingGmsCore() {
        val options = mapOf(
            7 to mapOf(
                "Change package name" to mapOf(
                    "updateProviders" to false,
                    "packageName" to "unsafe.old.package",
                )
            ),
            9 to mapOf("Some patch" to mapOf("enabled" to true)),
        )

        val result = SafeIntegrationProfile.enforceYouTubeOptions(
            sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
            patches = mapOf(
                7 to setOf("GmsCore support", "Hide ads", "Change package name"),
                9 to setOf("Some patch"),
            ),
            options = options,
        )

        assertEquals(
            SafeIntegrationProfile.patchedYouTubePackage,
            result.getValue(7).getValue("Change package name").getValue("packageName"),
        )
        assertEquals(false, result.getValue(7).getValue("Change package name")["updateProviders"])
        assertEquals(options.getValue(9), result.getValue(9))
    }

    @Test
    fun leavesNonYouTubeOptionsUntouched() {
        val options = mapOf(1 to mapOf("Patch" to mapOf("value" to "keep")))
        val result = SafeIntegrationProfile.enforceYouTubeOptions(
            sourcePackage = "com.example.other",
            patches = mapOf(1 to setOf("GmsCore support")),
            options = options,
        )

        assertSame(options, result)
    }

    @Test
    fun requiresSafeOutputIdentityForYouTubeGmsCoreRuns() {
        assertEquals(
            SafeIntegrationProfile.patchedYouTubePackage,
            SafeIntegrationProfile.expectedPatchedPackage(
                sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
                patches = mapOf(7 to setOf("GmsCore support")),
            ),
        )
        assertNull(
            SafeIntegrationProfile.expectedPatchedPackage(
                sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
                patches = mapOf(7 to setOf("Hide ads")),
            )
        )
        assertNull(
            SafeIntegrationProfile.expectedPatchedPackage(
                sourcePackage = "com.example.other",
                patches = mapOf(7 to setOf("GmsCore support")),
            )
        )
    }

    @Test
    fun makesPackageNamePatchExplicitForGmsCore() {
        val patches = mapOf(7 to setOf("GmsCore support", "Hide ads"))
        val available = mapOf(7 to setOf("GmsCore support", "Hide ads", "Change package name"))
        val result = SafeIntegrationProfile.enforceYouTubePatches(
            sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
            patches = patches,
            availablePatches = available,
        )

        assertEquals(
            setOf("GmsCore support", "Hide ads", "Change package name"),
            result.getValue(7),
        )
        assertSame(
            result,
            SafeIntegrationProfile.enforceYouTubePatches(
                sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
                patches = result,
                availablePatches = available,
            ),
        )
        assertSame(
            patches,
            SafeIntegrationProfile.enforceYouTubePatches(
                sourcePackage = "com.example.other",
                patches = patches,
                availablePatches = available,
            ),
        )
    }

    @Test
    fun currentBundleDoesNotInventRemovedPackagePatch() {
        // Morphe patches 1.43 publishes GmsCore support without the old standalone rename patch.
        val patches = mapOf(7 to setOf("GmsCore support", "Hide ads"))
        val result = SafeIntegrationProfile.enforceYouTubePatches(
            SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE, patches, patches,
        )
        assertSame(patches, result)
        assertEquals(
            SafeIntegrationProfile.patchedYouTubePackage,
            SafeIntegrationProfile.expectedPatchedPackage(SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE, result),
        )
    }

    @Test
    fun removesOnlyObsoleteProfileSelectionAndOption() {
        val result = SafeIntegrationProfile.enforceYouTubePatches(
            SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
            mapOf(7 to setOf("GmsCore support", "Change package name", "Missing user patch")),
            mapOf(7 to setOf("GmsCore support")),
        )
        assertEquals(setOf("GmsCore support", "Missing user patch"), result.getValue(7))
        val unrelated = mapOf("User patch" to mapOf("enabled" to true))
        val options = SafeIntegrationProfile.enforceYouTubeOptions(
            SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
            result,
            mapOf(
                7 to mapOf(
                    "Change package name" to mapOf("packageName" to "legacy.package"),
                    "GmsCore support" to mapOf("checkGmsCore" to true),
                ),
                9 to unrelated,
            ),
        )
        assertEquals(mapOf("GmsCore support" to mapOf("checkGmsCore" to true)), options.getValue(7))
        assertEquals(unrelated, options.getValue(9))
    }

    @Test
    fun missingBundleMetadataDoesNotSilentlyDropUserSelection() {
        val patches = mapOf(7 to setOf("GmsCore support", "Change package name"))
        assertSame(
            patches,
            SafeIntegrationProfile.enforceYouTubePatches(
                SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE, patches, emptyMap(),
            ),
        )
    }
}
