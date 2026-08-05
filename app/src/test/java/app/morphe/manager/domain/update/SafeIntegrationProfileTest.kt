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
                7 to setOf("GmsCore support", "Hide ads"),
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
        val result = SafeIntegrationProfile.enforceYouTubePatches(
            sourcePackage = SafeIntegrationProfile.GOOGLE_YOUTUBE_PACKAGE,
            patches = patches,
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
            ),
        )
        assertSame(
            patches,
            SafeIntegrationProfile.enforceYouTubePatches(
                sourcePackage = "com.example.other",
                patches = patches,
            ),
        )
    }
}
