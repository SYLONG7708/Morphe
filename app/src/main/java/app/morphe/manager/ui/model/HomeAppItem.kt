package app.morphe.manager.ui.model

import android.content.pm.PackageInfo
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.morphe.manager.data.room.apps.installed.InstalledApp
import java.io.File

/**
 * One card on the home screen: either an app, or one of the installs it was cloned into.
 *
 * Cloning gives an app a second install under a package name of its own, so a card is identified
 * by [id] rather than by [packageName], which several cards of the same app share and which the
 * bundle data is keyed by.
 */
@Immutable
data class HomeAppItem(
    val id: String,
    val packageName: String,
    val displayName: String,
    val gradientColors: List<Color>,
    val installedApp: InstalledApp?,
    val packageInfo: PackageInfo?,
    val isPinnedByDefault: Boolean,
    val isInstalledOnDevice: Boolean,
    val isDeleted: Boolean,
    val isInstallStateNotPatched: Boolean,
    val isInstallStateUnknown: Boolean,
    val isInstallStatePending: Boolean,
    val savedApkFile: File?,
    val hasUpdate: Boolean,
    val patchCount: Int,
    val isClone: Boolean
) {
    val hasSavedCopy: Boolean get() = savedApkFile != null

    /**
     * Whether the pending update is worth surfacing in the UI.
     * Uninstalled apps keep their update flag but show the uninstalled state instead.
     */
    val showsUpdateBadge: Boolean get() = hasUpdate &&
            !isDeleted &&
            !isInstallStateNotPatched &&
            !isInstallStateUnknown &&
            !isInstallStatePending
}

/**
 * What a home screen card is about, before anything is read off the device to describe it.
 *
 * @param isClone Whether the card is about a copy of the app rather than about the app itself.
 */
data class HomeAppSlot(
    val id: String,
    val packageName: String,
    val installedApp: InstalledApp?,
    val isClone: Boolean
)

/**
 * The cards one app is shown as: the app itself, followed by every further install of it, ordered
 * by package name so the list does not move around between reads.
 *
 * A rename is not what earns an install a card of its own. Patches rename an app for reasons of
 * their own, and such a build is still the install the app has, so it belongs on the app's card,
 * which is where the user goes to rebuild it. The app keeps that card even once its only installs
 * are copies, because it is the only place another copy can be made from.
 */
fun homeAppSlots(packageName: String, records: List<InstalledApp>): List<HomeAppSlot> {
    // Two records can describe the app's own install, a mount and a renamed build side by side.
    // The card goes to the one answering to the app's name, and the other keeps a card of its own
    // rather than dropping out of reach of the actions that manage it
    val own = records.firstOrNull { !it.isClone && it.currentPackageName == packageName }
        ?: records.filterNot { it.isClone }.minByOrNull { it.currentPackageName }
    val separate = records.filter { it.currentPackageName != own?.currentPackageName }

    return buildList {
        add(HomeAppSlot(packageName, packageName, own, isClone = false))
        separate.sortedBy { it.currentPackageName }.forEach { record ->
            add(HomeAppSlot(record.currentPackageName, packageName, record, record.isClone))
        }
    }
}
