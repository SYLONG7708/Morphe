/*
 * SyMorphe modification, 2026.
 * Upstream project: https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.util

import android.content.Context
import android.util.Log

/** This channel has no FCM delivery, so upstream update callers must use WorkManager even on GMS devices. */
fun Context.hasGooglePlayServices(): Boolean = false

/**
 * Compatibility shim retained for upstream call sites.
 *
 * SyMorphe deliberately uses WorkManager for every device, so the source tree and APK
 * require no Firebase project, google-services.json, API key, or push-topic configuration.
 */
fun syncFcmTopics(
    notificationsEnabled: Boolean,
    useManagerPrereleases: Boolean,
    usePatchesPrereleases: Boolean = false,
) {
    Log.d(
        "AutoPatch UpdateSync",
        "WorkManager enabled=$notificationsEnabled, " +
                "managerPrerelease=$useManagerPrereleases, patchesPrerelease=$usePatchesPrereleases"
    )
}
