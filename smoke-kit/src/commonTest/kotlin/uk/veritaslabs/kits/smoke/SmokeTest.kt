package uk.veritaslabs.kits.smoke

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun greetsByName() {
        // Arrange
        val name = "kits"

        // Act
        val greeting = Smoke.greet(name)

        // Assert
        assertEquals("hello, kits", greeting)
    }
}
