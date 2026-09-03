package app.morphe.manager.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagerArtifactResolutionTest {
    @Test
    fun `selects update for the installed manager edition`() {
        val licensed = artifact("licensed.apk", "com.sylong.symorphe")
        val noLicense = artifact("no-license.apk", "com.sylong.symorphe.nolicense")

        assertEquals(
            noLicense,
            resolveManagerArtifact(
                listOf(licensed, noLicense),
                DeviceProfile(33, listOf("arm64-v8a")),
                "com.sylong.symorphe.nolicense",
            ),
        )
    }

    private fun artifact(name: String, packageName: String) = UpdateArtifact(
        name = name,
        url = "https://github.com/SYLONG7708/Morphe/releases/download/test/$name",
        sha256 = "A".repeat(64),
        size = 1,
        packageName = packageName,
        minSdk = 26,
        abis = setOf("arm64-v8a"),
    )
}
