package uk.veritaslabs.kits.blobs

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `blob.want` message as peers on other builds send it. The field is named `homeId` on the wire although the code says
 * `spaceId`: a device that has not updated sends and expects the old name, so it must not change.
 */
class BlobMessageWireTest {

    private val message = BlobMessage.Want("space-1", "abc123")
    private val literal = """{"kind":"blob.want","homeId":"space-1","hash":"abc123"}"""

    @Test
    fun theBlobWantMessageEncodesToItsLiteralJsonWithTheOldFieldName() {
        // Arrange
        val expected = literal

        // Act
        val encoded = BlobMessage.encode(message).decodeToString()

        // Assert
        assertEquals(expected, encoded)
    }

    @Test
    fun theLiteralJsonOfAnOlderBuildDecodesBack() {
        // Arrange
        val payload = Base64.Default.encode(literal.encodeToByteArray())

        // Act
        val decoded = BlobMessage.decode(payload)

        // Assert
        assertEquals(message, decoded)
    }
}
