/*
 * AutoPatch Hub modification, 2026.
 */

package app.morphe.manager.domain.update

import android.app.Application
import android.os.Build
import app.morphe.manager.BuildConfig
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.data.platform.NetworkInfo
import app.morphe.manager.domain.installer.InstallResult
import app.morphe.manager.domain.installer.InstallerManager
import app.morphe.manager.domain.installer.RootInstaller
import app.morphe.manager.domain.installer.SessionInstaller
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.InstalledAppRepository
import app.morphe.manager.domain.repository.OriginalApkRepository
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.network.utils.getOrThrow
import app.morphe.manager.util.PM
import app.morphe.manager.util.compareVersions
import app.morphe.manager.util.sha256OrNull
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class InstallableUpdate {
    MANAGER,
    MICROG,
}

/**
 * One source of truth for Manager, patch-bundle, YouTube source, and MicroG updates.
 *
 * The coordinator never downloads a Google YouTube APK. It only inspects an installed or
 * previously saved original APK, then lets the existing on-device patcher process it when the
 * exact version is supported by the current patch bundle.
 */
class EcosystemUpdateCoordinator(
    private val app: Application,
    private val manifestRepository: SignedUpdateManifestRepository,
    private val http: HttpService,
    private val verifier: DetachedSignatureVerifier,
    private val filesystem: Filesystem,
    private val networkInfo: NetworkInfo,
    private val prefs: PreferencesManager,
    private val pm: PM,
    private val patchBundleRepository: PatchBundleRepository,
    private val installedAppRepository: InstalledAppRepository,
    private val originalApkRepository: OriginalApkRepository,
    private val sessionInstaller: SessionInstaller,
    private val rootInstaller: RootInstaller,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<EcosystemUpdateState>(EcosystemUpdateState.Idle)
    val state: StateFlow<EcosystemUpdateState> = mutableState.asStateFlow()

    suspend fun refresh(
        downloadAssets: Boolean = true,
        allowUnsafeNetwork: Boolean = false,
    ): EcosystemUpdateSnapshot = mutex.withLock {
        mutableState.value = EcosystemUpdateState.Checking
        try {
            patchBundleRepository.updateCheckAndAwait(allowUnsafeNetwork)
            val manifest = manifestRepository.fetch()
            val profile = DeviceProfile(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList())

            var manager = resolveManager(manifest.manager, profile)
            var microg = resolveMicroG(manifest.microg, profile)

            val mayUseCurrentNetwork =
                prefs.allowMeteredUpdates.get() || !networkInfo.isMetered()
            if (downloadAssets &&
                prefs.automaticEcosystemUpdates.get() &&
                mayUseCurrentNetwork
            ) {
                coroutineScope {
                    val managerJob = async { prepareIfNeeded(manager) }
                    val microgJob = async { prepareIfNeeded(microg) }
                    manager = managerJob.await()
                    microg = microgJob.await()
                }
            }

            val snapshot = EcosystemUpdateSnapshot(
                checkedAt = System.currentTimeMillis(),
                sequence = manifest.sequence,
                manager = manager,
                patches = resolvePatches(manifest.patches),
                microg = microg,
                youtube = resolveYouTube(),
            )
            mutableState.value = EcosystemUpdateState.Ready(snapshot)
            snapshot
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            mutableState.value = EcosystemUpdateState.Failure(message)
            throw error
        }
    }

    suspend fun installPrepared(component: InstallableUpdate): InstallResult {
        val snapshot = (state.value as? EcosystemUpdateState.Ready)?.snapshot
            ?: refresh(downloadAssets = true)
        val componentState = when (component) {
            InstallableUpdate.MANAGER -> snapshot.manager
            InstallableUpdate.MICROG -> snapshot.microg
        }
        val artifact = requireNotNull(componentState.artifact) { "No compatible update artifact" }
        val localFile = requireNotNull(componentState.localPath?.let(::File)) {
            "Update has not been downloaded"
        }
        check(validateDownloadedArtifact(localFile, artifact)) {
            "Prepared update failed validation"
        }

        updateComponentState(component, componentState.copy(status = UpdateStatus.INSTALLING))
        val expectedPackage = requireNotNull(artifact.packageName)

        val result = when {
            sessionInstaller.shizukuAvailability(InstallerManager.InstallTarget.MANAGER_UPDATE).available ->
                sessionInstaller.installShizuku(localFile, expectedPackage)

            rootInstaller.hasRootAccess() -> {
                rootInstaller.installAsPlayStore(localFile)
                InstallResult.Success
            }

            else -> {
                // Android deliberately requires confirmation for ordinary installers. Launching
                // the system UI is the maximum automation available without privileged access.
                sessionInstaller.launchIntentInstall(localFile)
                InstallResult.Success
            }
        }

        if (result !is InstallResult.Success) {
            updateComponentState(component, componentState.copy(status = UpdateStatus.ERROR))
        }
        return result
    }

    private fun updateComponentState(
        component: InstallableUpdate,
        value: ComponentUpdateState,
    ) {
        val current = (mutableState.value as? EcosystemUpdateState.Ready)?.snapshot ?: return
        mutableState.value = EcosystemUpdateState.Ready(
            when (component) {
                InstallableUpdate.MANAGER -> current.copy(manager = value)
                InstallableUpdate.MICROG -> current.copy(microg = value)
            }
        )
    }

    private fun resolveManager(
        component: UpdateComponent,
        profile: DeviceProfile,
    ): ComponentUpdateState {
        val artifact = profile.resolve(component.artifacts)
            ?: return ComponentUpdateState(
                status = UpdateStatus.UNSUPPORTED,
                installedVersion = BuildConfig.VERSION_NAME,
                availableVersion = component.version,
                detail = "No artifact matches SDK ${profile.sdk} / ${profile.abis.joinToString()}",
            )
        val remoteCode = artifact.versionCode ?: component.versionCode
        val updateAvailable = when {
            remoteCode != null -> remoteCode > BuildConfig.VERSION_CODE
            else -> compareVersions(component.version, BuildConfig.VERSION_NAME) > 0
        }
        return ComponentUpdateState(
            status = if (updateAvailable) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE,
            installedVersion = BuildConfig.VERSION_NAME,
            availableVersion = component.version,
            artifact = artifact,
        )
    }

    private fun resolveMicroG(
        component: UpdateComponent,
        profile: DeviceProfile,
    ): ComponentUpdateState {
        val artifact = profile.resolve(component.artifacts)
            ?: return ComponentUpdateState(
                status = UpdateStatus.UNSUPPORTED,
                availableVersion = component.version,
                detail = "No MicroG artifact matches this device",
            )
        val packageName = artifact.packageName ?: MICROG_PACKAGE
        val installed = pm.getPackageInfo(packageName)
        val installedCode = installed?.let(pm::getVersionCode)
        val remoteCode = artifact.versionCode ?: component.versionCode
        val updateAvailable = installed == null || remoteCode == null ||
                installedCode == null || remoteCode > installedCode
        if (installed != null && updateAvailable && artifact.signerSha256.isNotEmpty()) {
            val installedSigners = pm.getInstalledSignatureHashes(packageName)
                .map(String::lowercase)
                .toSet()
            val updateSigners = artifact.signerSha256
                .map(String::lowercase)
                .toSet()
            if (installedSigners.isNotEmpty() && installedSigners.intersect(updateSigners).isEmpty()) {
                return ComponentUpdateState(
                    status = UpdateStatus.UNSUPPORTED,
                    installedVersion = installed.versionName,
                    availableVersion = component.version,
                    artifact = artifact,
                    detail = "Installed MicroG uses a different signing certificate; " +
                        "Android cannot safely replace it",
                )
            }
        }
        return ComponentUpdateState(
            status = if (updateAvailable) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE,
            installedVersion = installed?.versionName,
            availableVersion = component.version,
            artifact = artifact,
            detail = if (installed == null) "MicroG is required for non-root YouTube" else null,
        )
    }

    private suspend fun resolvePatches(component: UpdateComponent): ComponentUpdateState {
        val localVersion = patchBundleRepository.sources.first()
            .firstOrNull { it.uid == PatchBundleRepository.DEFAULT_SOURCE_UID }
            ?.version
        val current = localVersion != null && compareVersions(localVersion, component.version) >= 0
        return ComponentUpdateState(
            status = if (current) UpdateStatus.UP_TO_DATE else UpdateStatus.UPDATE_AVAILABLE,
            installedVersion = localVersion,
            availableVersion = component.version,
            artifact = component.artifacts.firstOrNull(),
        )
    }

    /**
     * Inspects the local YouTube source and the currently installed Morphe YouTube.
     *
     * This deliberately performs no download and is safe to call from the home-screen
     * auto-patch entry point after the patch-bundle pipeline has finished loading.
     */
    suspend fun inspectYouTube(): ComponentUpdateState = resolveYouTube()

    private suspend fun resolveYouTube(): ComponentUpdateState {
        val source = resolveCompatibleYouTubeSource()
            ?: return ComponentUpdateState(
                status = UpdateStatus.WAITING_FOR_COMPATIBLE_SOURCE,
                installedVersion = installedYouTubeVersion(PATCHED_YOUTUBE_PACKAGE),
                detail = "Waiting for an installed or saved YouTube version supported by the patch bundle",
            )

        val records = installedAppRepository.getAll().first()
        val record = records.firstOrNull { it.originalPackageName == YOUTUBE_PACKAGE }
        val patchedPackage = record?.currentPackageName ?: PATCHED_YOUTUBE_PACKAGE
        val patchedInfo = pm.getPackageInfo(patchedPackage)
        val sourceChanged = patchedInfo?.versionName != source.versionName
        val bundleChanged = if (record == null) {
            patchedInfo != null
        } else {
            val usedVersions = installedAppRepository.getBundleVersionsForApp(record.currentPackageName)
            val currentVersions = patchBundleRepository.sources.first().associate { it.uid to it.version }
            usedVersions.isEmpty() || usedVersions.any { (uid, used) ->
                used != null && currentVersions[uid] != null && currentVersions[uid] != used
            }
        }
        val updateNeeded = patchedInfo == null || sourceChanged || bundleChanged

        return ComponentUpdateState(
            status = if (updateNeeded) UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE,
            installedVersion = patchedInfo?.versionName,
            availableVersion = source.versionName,
            detail = when {
                patchedInfo == null -> "Ready for first local patch"
                sourceChanged -> "Compatible YouTube source update detected"
                bundleChanged -> "New patch bundle detected"
                else -> "Patched YouTube is current"
            },
        )
    }

    private data class YouTubeSource(val versionName: String, val versionCode: Long?)

    private suspend fun resolveCompatibleYouTubeSource(): YouTubeSource? {
        val installed = pm.getPackageInfo(YOUTUBE_PACKAGE)
        if (installed != null) {
            val candidate = YouTubeSource(installed.versionName.orEmpty(), pm.getVersionCode(installed))
            if (candidate.versionName.isNotBlank() && youtubeVersionSupported(candidate)) return candidate
        }

        val saved = originalApkRepository.get(YOUTUBE_PACKAGE) ?: return null
        val file = File(saved.filePath).takeIf(File::isFile) ?: return null
        val archive = pm.getPackageInfo(file) ?: return null
        val candidate = YouTubeSource(
            archive.versionName ?: saved.version,
            pm.getVersionCode(archive),
        )
        return candidate.takeIf { it.versionName.isNotBlank() && youtubeVersionSupported(it) }
    }

    private suspend fun youtubeVersionSupported(source: YouTubeSource): Boolean =
        patchBundleRepository
            .scopedBundleInfoFlow(YOUTUBE_PACKAGE, source.versionName, source.versionCode)
            .first()
            .any { it.enabled && it.compatible.isNotEmpty() }

    private fun installedYouTubeVersion(packageName: String): String? =
        pm.getPackageInfo(packageName)?.versionName

    private suspend fun prepareIfNeeded(state: ComponentUpdateState): ComponentUpdateState {
        if (state.status != UpdateStatus.UPDATE_AVAILABLE) return state
        val artifact = state.artifact ?: return state.copy(
            status = UpdateStatus.UNSUPPORTED,
            detail = "No compatible artifact",
        )
        return runCatching {
            val file = downloadVerified(artifact)
            state.copy(status = UpdateStatus.DOWNLOADED, localPath = file.absolutePath)
        }.getOrElse { error ->
            state.copy(status = UpdateStatus.ERROR, detail = error.message)
        }
    }

    private suspend fun downloadVerified(artifact: UpdateArtifact): File =
        withContext(Dispatchers.IO) {
            validateArtifactUrl(artifact.url)
            val safeName = artifact.name
                .substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(safeName.endsWith(".apk", ignoreCase = true)) {
                "Installable artifact must be an APK"
            }

            val destination = filesystem.updatesDir.resolve(safeName)
            if (destination.isFile && validateDownloadedArtifact(destination, artifact)) {
                return@withContext destination
            }

            val partial = filesystem.updatesDir.resolve("$safeName.part")
            partial.delete()
            http.downloadToFile(
                saveLocation = partial,
                builder = { url(artifact.url) },
            )
            check(validateDownloadedArtifact(partial, artifact)) {
                "Downloaded ${artifact.name} failed integrity or compatibility verification"
            }

            artifact.signatureUrl?.let { signatureUrl ->
                validateArtifactUrl(signatureUrl)
                val detached = http.request<String> {
                    url(signatureUrl)
                    header(HttpHeaders.CacheControl, "no-cache")
                }.getOrThrow()
                check(verifier.verifyFile(partial, detached)) {
                    "Detached signature verification failed for ${artifact.name}"
                }
            }

            runCatching {
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            destination
        }

    private fun validateDownloadedArtifact(file: File, artifact: UpdateArtifact): Boolean {
        if (!file.isFile) return false
        if (artifact.size > 0 && file.length() != artifact.size) return false
        val hash = file.sha256OrNull() ?: return false
        if (!hash.equals(artifact.sha256, ignoreCase = true)) return false

        val packageName = artifact.packageName ?: return true
        val info = pm.getPackageInfo(file) ?: return false
        if (info.packageName != packageName) return false
        artifact.versionName?.let { expected ->
            if (info.versionName != expected) return false
        }
        artifact.versionCode?.let { expected ->
            if (pm.getVersionCode(info) != expected) return false
        }
        info.applicationInfo?.let { applicationInfo ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                applicationInfo.minSdkVersion > Build.VERSION.SDK_INT
            ) return false
        }

        if (artifact.signerSha256.isNotEmpty()) {
            val actual = pm.getApkFileSignatureHashes(file).map(String::lowercase).toSet()
            val expected = artifact.signerSha256.map(String::lowercase).toSet()
            if (actual.intersect(expected).isEmpty()) return false
        }

        val installedSigners = pm.getInstalledSignatureHashes(packageName)
        if (installedSigners.isNotEmpty()) {
            val archiveSigners = pm.getApkFileSignatureHashes(file)
            if (installedSigners.intersect(archiveSigners).isEmpty()) return false
        }
        return true
    }

    private fun validateArtifactUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Update URL must use HTTPS" }
        require(uri.host?.lowercase() in ALLOWED_DOWNLOAD_HOSTS) {
            "Untrusted update host: ${uri.host}"
        }
        require(uri.userInfo == null && uri.fragment == null) { "Malformed update URL" }
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val PATCHED_YOUTUBE_PACKAGE = "app.morphe.android.youtube"
        const val MICROG_PACKAGE = "app.revanced.android.gms"

        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "raw.githubusercontent.com",
        )
    }
}
