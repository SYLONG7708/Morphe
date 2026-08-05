/*
 * SyMorphe modification, 2026.
 */

package app.morphe.manager.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val schema: Int,
    val sequence: Long,
    val channel: String,
    @SerialName("published_at") val publishedAt: String,
    val manager: UpdateComponent,
    val patches: UpdateComponent,
    val microg: UpdateComponent,
)

@Serializable
data class UpdateComponent(
    val version: String,
    @SerialName("version_code") val versionCode: Long? = null,
    val artifacts: List<UpdateArtifact>,
)

@Serializable
data class UpdateArtifact(
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long,
    @SerialName("signature_url") val signatureUrl: String? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("version_code") val versionCode: Long? = null,
    @SerialName("min_sdk") val minSdk: Int = 1,
    @SerialName("max_sdk") val maxSdk: Int? = null,
    val abis: Set<String> = emptySet(),
    @SerialName("signer_sha256") val signerSha256: Set<String> = emptySet(),
)

data class DeviceProfile(
    val sdk: Int,
    val abis: List<String>,
) {
    fun resolve(artifacts: List<UpdateArtifact>): UpdateArtifact? =
        artifacts
            .asSequence()
            .filter { sdk >= it.minSdk && (it.maxSdk == null || sdk <= it.maxSdk) }
            .filter { it.abis.isEmpty() || abis.any(it.abis::contains) }
            .sortedWith(
                compareByDescending<UpdateArtifact> { artifact ->
                    artifact.abis.isNotEmpty() && abis.firstOrNull() in artifact.abis
                }.thenBy { it.abis.size }
            )
            .firstOrNull()
}

enum class UpdateStatus {
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADED,
    INSTALLING,
    WAITING_FOR_COMPATIBLE_SOURCE,
    NOT_INSTALLED,
    UNSUPPORTED,
    ERROR,
}

data class ComponentUpdateState(
    val status: UpdateStatus,
    val installedVersion: String? = null,
    val availableVersion: String? = null,
    val artifact: UpdateArtifact? = null,
    val localPath: String? = null,
    val detail: String? = null,
)

data class EcosystemUpdateSnapshot(
    val checkedAt: Long,
    val sequence: Long,
    val manager: ComponentUpdateState,
    val patches: ComponentUpdateState,
    val microg: ComponentUpdateState,
    val youtube: ComponentUpdateState,
)

sealed interface EcosystemUpdateState {
    data object Idle : EcosystemUpdateState
    data object Checking : EcosystemUpdateState
    data class Ready(val snapshot: EcosystemUpdateSnapshot) : EcosystemUpdateState
    data class Failure(val message: String) : EcosystemUpdateState
}
