package app.morphe.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppIntegrityTest {
    private val fingerprint = "A".repeat(64)

    @Test
    fun acceptsOnlyThePinnedSigningCertificate() {
        assertTrue(AppIntegrity.matchesExpected(fingerprint, listOf(fingerprint.lowercase())))
        assertTrue(AppIntegrity.matchesExpected(fingerprint.chunked(2).joinToString(":"), listOf(fingerprint)))
        assertFalse(AppIntegrity.matchesExpected(fingerprint, listOf("B".repeat(64))))
    }

    @Test
    fun rejectsMissingOrMalformedPins() {
        assertFalse(AppIntegrity.matchesExpected("", listOf(fingerprint)))
        assertFalse(AppIntegrity.matchesExpected("not-a-certificate", listOf(fingerprint)))
    }
}
