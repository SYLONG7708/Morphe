/*
 * SyMorphe UIS7870 safe coexistence profile, 2026.
 */

package app.morphe.manager.domain.update

import app.morphe.manager.BuildConfig
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection

/**
 * One runtime source of truth for the packages produced by the UIS7870 integration chain.
 *
 * A matching derived patch bundle and MicroG-RE APK are required. The release workflow builds
 * both from pinned upstream tags using config/uis7870-safe.properties.
 */
object SafeIntegrationProfile {
    const val GOOGLE_YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val GMS_CORE_SUPPORT_PATCH = "GmsCore support"
    const val CHANGE_PACKAGE_NAME_PATCH = "Change package name"
    const val PACKAGE_NAME_OPTION = "packageName"

    val vendorGroup: String = BuildConfig.SAFE_VENDOR_GROUP
    val patchedYouTubePackage: String = BuildConfig.SAFE_PATCHED_YOUTUBE_PACKAGE
    val microgPackage: String = BuildConfig.SAFE_MICROG_PACKAGE
    val revision: Int = BuildConfig.SAFE_PROFILE_REVISION

    init {
        require(patchedYouTubePackage == "$vendorGroup.android.youtube")
        require(microgPackage == "$vendorGroup.android.gms")
    }

    /**
     * Forces every selected YouTube GmsCore patch to emit the package identity paired with the
     * derived MicroG build. Existing user options and unrelated bundles remain unchanged.
     */
    fun enforceYouTubeOptions(
        sourcePackage: String,
        patches: PatchSelection,
        options: Options,
    ): Options {
        if (sourcePackage != GOOGLE_YOUTUBE_PACKAGE) return options

        val gmsCoreBundleUids = patches
            .filterValues { names ->
                names.any { it.equals(GMS_CORE_SUPPORT_PATCH, ignoreCase = true) }
            }
            .keys
        if (gmsCoreBundleUids.isEmpty()) return options

        return buildMap {
            putAll(options)
            gmsCoreBundleUids.forEach { bundleUid ->
                val bundleOptions = get(bundleUid).orEmpty().toMutableMap()
                val packageOptions = bundleOptions[CHANGE_PACKAGE_NAME_PATCH]
                    .orEmpty()
                    .toMutableMap()
                packageOptions[PACKAGE_NAME_OPTION] = patchedYouTubePackage
                bundleOptions[CHANGE_PACKAGE_NAME_PATCH] = packageOptions
                put(bundleUid, bundleOptions)
            }
        }
    }

    /**
     * Makes the package-name patch explicit so its safe option is applied even when it would
     * otherwise be loaded only as an implicit dependency of GmsCore support.
     */
    fun enforceYouTubePatches(
        sourcePackage: String,
        patches: PatchSelection,
    ): PatchSelection {
        if (sourcePackage != GOOGLE_YOUTUBE_PACKAGE) return patches

        var changed = false
        val safePatches = patches.mapValues { (_, names) ->
            if (
                names.any { it.equals(GMS_CORE_SUPPORT_PATCH, ignoreCase = true) } &&
                names.none { it.equals(CHANGE_PACKAGE_NAME_PATCH, ignoreCase = true) }
            ) {
                changed = true
                names + CHANGE_PACKAGE_NAME_PATCH
            } else {
                names
            }
        }
        return if (changed) safePatches else patches
    }

    /**
     * Returns the only package identity accepted from a YouTube patch run that includes
     * GmsCore support. Null means this safe profile does not own the requested patch run.
     */
    fun expectedPatchedPackage(
        sourcePackage: String,
        patches: PatchSelection,
    ): String? {
        if (sourcePackage != GOOGLE_YOUTUBE_PACKAGE) return null
        val usesGmsCore = patches.values.any { names ->
            names.any { it.equals(GMS_CORE_SUPPORT_PATCH, ignoreCase = true) }
        }
        return if (usesGmsCore) patchedYouTubePackage else null
    }
}
