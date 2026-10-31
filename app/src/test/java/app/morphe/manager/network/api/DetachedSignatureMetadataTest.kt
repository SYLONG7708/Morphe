package app.morphe.manager.network.api

import app.morphe.manager.network.dto.ManagerReleaseInfo
import app.morphe.manager.network.dto.MorpheAsset
import app.morphe.manager.network.dto.PatchesReleaseInfo
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetachedSignatureMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val signatureUrl = "https://example.invalid/patches.mpp.sig"
    private val metadata = """
        {
          "version": "2.0.0.7870.2",
          "download_url": "https://example.invalid/patches.mpp",
          "created_at": "2026-10-31T00:00:00",
          "description": "Signed channel fixture",
          "signature_download_url": "$signatureUrl"
        }
    """.trimIndent()

    @Test fun `patch release JSON retains the detached signature URL`() {
        assertEquals(signatureUrl, json.decodeFromString<PatchesReleaseInfo>(metadata).signatureDownloadUrl)
    }

    @Test fun `manager release JSON retains the detached signature URL`() {
        assertEquals(signatureUrl, json.decodeFromString<ManagerReleaseInfo>(metadata).signatureDownloadUrl)
    }

    @Test fun `API assets retain the detached signature URL`() {
        assertEquals(signatureUrl, json.decodeFromString<MorpheAsset>(metadata).signatureDownloadUrl)
    }

    @Test fun `inline bundle signing metadata does not replace the detached signature`() {
        val combined = metadata.replace("\"description\":", "\"public_key\": \"fixture-key\", \"description\":")
        assertEquals(signatureUrl, json.decodeFromString<PatchesReleaseInfo>(combined).signatureDownloadUrl)
        assertEquals(signatureUrl, json.decodeFromString<MorpheAsset>(combined).signatureDownloadUrl)
    }

    @Test fun `legacy metadata without a detached signature remains readable`() {
        val legacy = metadata.replace(",\n  \"signature_download_url\": \"$signatureUrl\"", "")
        assertNull(json.decodeFromString<PatchesReleaseInfo>(legacy).signatureDownloadUrl)
        assertNull(json.decodeFromString<ManagerReleaseInfo>(legacy).signatureDownloadUrl)
        assertNull(json.decodeFromString<MorpheAsset>(legacy).signatureDownloadUrl)
    }
}
