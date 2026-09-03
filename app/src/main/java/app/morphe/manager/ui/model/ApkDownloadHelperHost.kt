/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.model

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import app.morphe.manager.util.ApkDownloadHelperContract
import app.morphe.patcher.patch.ApkFileType

/**
 * The patching flow an APK download helper is asked on behalf of.
 *
 * Single-app patching and the queue both offer the same helper picker, each for the app it has a
 * download open for. What a helper may and may not do is settled by [ApkDownloadHelperContract];
 * what is left here is the part only the flow itself can answer, which is which app is being
 * patched and where the result goes.
 */
interface ApkDownloadHelperHost {
    /**
     * Whether the APK about to arrive will have its signature checked, which is what the picker
     * warns about. False on Android 8-10, where signatures cannot be read from an archive, and
     * for an app no bundle declares signatures for.
     */
    val helperSignatureCheckAvailable: Boolean

    /**
     * Describes the original APK for the helper the user picked, or null once the flow has moved
     * on and there is no app left to ask about.
     *
     * @param component The helper activity the user picked, addressed explicitly.
     */
    fun createApkDownloadHelperIntent(component: ComponentName): Intent?

    /** Takes the archive a helper downloaded, which still runs through the usual checks. */
    fun onHelperApkReceived(uri: Uri)

    /** Takes a helper's answer that the user installed the app from an app store instead. */
    fun onHelperInstalledAppChosen(packageName: String)
}

/** The archive format a bundle asks for, named the way the helper protocol spells it. */
internal fun ApkFileType.toHelperFileType() = when {
    isApk -> ApkDownloadHelperContract.FILE_TYPE_APK
    isApkM -> ApkDownloadHelperContract.FILE_TYPE_APKM
    isApkS -> ApkDownloadHelperContract.FILE_TYPE_APKS
    isXApk -> ApkDownloadHelperContract.FILE_TYPE_XAPK
    else -> null
}
