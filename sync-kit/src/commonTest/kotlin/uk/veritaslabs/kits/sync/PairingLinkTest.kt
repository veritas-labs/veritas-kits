package uk.veritaslabs.kits.sync

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val Scheme = "app://pair"

class PairingLinkTest {

    @Test
    fun encodeThenDecodeRoundTrips() {
        // Arrange
        val link = PairingLink(agentId = "aa11bb22cc33dd44", expiresAt = 2_000L)

        // Act
        val decoded = PairingLink.decode(link.encode(Scheme), Scheme)

        // Assert
        assertEquals(link, decoded)
    }

    @Test
    fun encodeProducesTheSchemeUri() {
        // Arrange
        val link = PairingLink(agentId = "aa11bb22cc33dd44", expiresAt = 2_000L)

        // Act
        val encoded = link.encode(Scheme)

        // Assert
        assertTrue(encoded.startsWith("app://pair/"))
    }

    @Test
    fun decodeRejectsUnrecognisedScheme() {
        // Arrange
        val raw = "not-a-pairing-link"

        // Act
        val decoded = PairingLink.decode(raw, Scheme)

        // Assert
        assertNull(decoded)
    }

    @Test
    fun decodeRejectsUndecodableToken() {
        // Arrange
        val raw = "app://pair/not-valid-base64!!"

        // Act
        val decoded = PairingLink.decode(raw, Scheme)

        // Assert
        assertNull(decoded)
    }

    @Test
    fun decodeRejectsMissingPublicKey() {
        // Arrange
        val raw = "app://pair/${Base64.UrlSafe.encode(":2000".encodeToByteArray())}"

        // Act
        val decoded = PairingLink.decode(raw, Scheme)

        // Assert
        assertNull(decoded)
    }

    @Test
    fun decodeRejectsMissingExpiry() {
        // Arrange
        val raw = "app://pair/${Base64.UrlSafe.encode("aa11bb22cc33dd44".encodeToByteArray())}"

        // Act
        val decoded = PairingLink.decode(raw, Scheme)

        // Assert
        assertNull(decoded)
    }

    @Test
    fun decodeRejectsNonNumericExpiry() {
        // Arrange
        val raw = "app://pair/${Base64.UrlSafe.encode("aa11bb22cc33dd44:not-a-number".encodeToByteArray())}"

        // Act
        val decoded = PairingLink.decode(raw, Scheme)

        // Assert
        assertNull(decoded)
    }

    @Test
    fun isExpiredHonoursExpiryBoundary() {
        // Arrange
        val link = PairingLink(agentId = "aa11bb22cc33dd44", expiresAt = 2_000L)

        // Act
        val expiredAtBoundary = link.isExpired(2_000L)
        val stillValidBeforeBoundary = link.isExpired(1_999L)

        // Assert
        assertTrue(expiredAtBoundary)
        assertFalse(stillValidBeforeBoundary)
    }
}
