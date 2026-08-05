/*
 * SyMorphe UIS7870 all-in-one provisioning, 2026.
 */

package app.morphe.manager.domain.update

import android.app.Application
import android.util.Log
import app.morphe.manager.BuildConfig
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.util.PM
import app.morphe.manager.util.sha256OrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class BundledEcosystem(
    val patchVersion: String,
    val microg: ComponentUpdateState,
)

/**
 * Extracts and verifies the two redistributable dependencies embedded in an all-in-one build.
 *
 * YouTube itself is intentionally not bundled. The Hub extracts the already-installed original
 * from the user's device and patches it locally after Android has confirmed the MicroG install.
 */
class BundledEcosystemProvisioner(
    private val app: Application,
    private val pm: PM,
    private val patchBundleRepository: PatchBundleRepository,
) {
    private val mutex = Mutex()

    suspend fun provision(): BundledEcosystem = mutex.withLock {
        check(BuildConfig.BUNDLED_ECOSYSTEM_ENABLED) {
            "This Manager build does not contain a bundled UIS7870 ecosystem"
        }

        Log.i(TAG, "Provisioning embedded patch bundle")
        val patchVersion = patchBundleRepository.installBundledDefault(
            expectedSha256 = BuildConfig.BUNDLED_PATCH_SHA256,
            expectedVersion = BuildConfig.BUNDLED_PATCH_VERSION,
            createStream = { app.assets.open(BuildConfig.BUNDLED_PATCH_ASSET) },
        )
        Log.i(TAG, "Embedded patch bundle ready: $patchVersion")
        Log.i(TAG, "Extracting embedded MicroG")
        val microgFile = extractVerifiedAsset(
            assetName = BuildConfig.BUNDLED_MICROG_ASSET,
            expectedSha256 = BuildConfig.BUNDLED_MICROG_SHA256,
            outputName = "microg.apk",
        )
        Log.i(TAG, "Embedded MicroG extracted and hash verified")
        val microg = resolveMicrog(microgFile)
        Log.i(TAG, "Embedded MicroG state: ${microg.status} (${microg.availableVersion})")
        BundledEcosystem(
            patchVersion = patchVersion,
            microg = microg,
        )
    }

    private fun resolveMicrog(file: File): ComponentUpdateState {
        val archive = requireNotNull(pm.getPackageInfo(file)) {
            "Bundled MicroG is not a readable APK"
        }
        require(archive.packageName == SafeIntegrationProfile.microgPackage) {
            "Bundled MicroG package ${archive.packageName} does not match " +
                SafeIntegrationProfile.microgPackage
        }
        val archiveVersionCode = pm.getVersionCode(archive)
        val archiveSigners = pm.getApkFileSignatureHashes(file)
        check(archiveSigners.isNotEmpty()) {
            "Bundled MicroG signing certificate could not be verified"
        }

        val installed = pm.getPackageInfo(archive.packageName)
        val installedSigners = pm.getInstalledSignatureHashes(archive.packageName)
        if (
            installed != null &&
            installedSigners.isNotEmpty() &&
            installedSigners.intersect(archiveSigners).isEmpty()
        ) {
            return ComponentUpdateState(
                status = UpdateStatus.UNSUPPORTED,
                installedVersion = installed.versionName,
                availableVersion = archive.versionName,
                detail = "Installed MicroG uses a different signing certificate; " +
                    "Android cannot safely replace it",
            )
        }

        val installedVersionCode = installed?.let(pm::getVersionCode)
        val needsInstall = installedVersionCode == null || archiveVersionCode > installedVersionCode
        val artifact = UpdateArtifact(
            name = "microg.apk",
            url = "asset://${BuildConfig.BUNDLED_MICROG_ASSET}",
            sha256 = BuildConfig.BUNDLED_MICROG_SHA256,
            size = file.length(),
            packageName = archive.packageName,
            versionName = archive.versionName,
            versionCode = archiveVersionCode,
            minSdk = archive.applicationInfo?.minSdkVersion ?: 1,
            signerSha256 = archiveSigners,
        )
        return ComponentUpdateState(
            status = if (needsInstall) UpdateStatus.DOWNLOADED else UpdateStatus.UP_TO_DATE,
            installedVersion = installed?.versionName,
            availableVersion = archive.versionName,
            artifact = artifact,
            localPath = file.absolutePath,
            detail = if (installed == null) {
                "Bundled MicroG is ready for Android installation confirmation"
            } else {
                null
            },
        )
    }

    private suspend fun extractVerifiedAsset(
        assetName: String,
        expectedSha256: String,
        outputName: String,
    ): File = withContext(Dispatchers.IO) {
        require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "Invalid bundled asset SHA-256"
        }
        val directory = app.filesDir.resolve("bundled_ecosystem").also(File::mkdirs)
        val target = directory.resolve(outputName)
        if (target.sha256OrNull()?.equals(expectedSha256, ignoreCase = true) == true) {
            return@withContext target
        }

        val candidate = directory.resolve("$outputName.part")
        candidate.delete()
        try {
            app.assets.open(assetName).use { input ->
                candidate.outputStream().use(input::copyTo)
            }
            check(candidate.sha256OrNull()?.equals(expectedSha256, ignoreCase = true) == true) {
                "Bundled asset hash mismatch: $assetName"
            }
            runCatching {
                Files.move(
                    candidate.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(
                    candidate.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            runCatching { candidate.delete() }
        }
        check(target.sha256OrNull()?.equals(expectedSha256, ignoreCase = true) == true) {
            "Extracted bundled asset failed verification: $assetName"
        }
        target
    }

    private companion object {
        const val TAG = "AutoPatch Bundled"
    }
}
