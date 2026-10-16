package uk.veritaslabs.kits.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DocsKitBoundaryTest {

    @Test
    fun theDocsKitImportsNothingOfVesta() {
        // Arrange
        val dir = listOf("src/jvmAndroidMain/kotlin/uk/veritaslabs/kits/docs", "docs-kit/src/jvmAndroidMain/kotlin/uk/veritaslabs/kits/docs")
            .map(::File).first { it.isDirectory }

        // Act
        val offending = dir.walk().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { it.startsWith("import uk.veritaslabs.vesta.") }.map { "${file.name}: $it" }
        }.toList()

        // Assert
        assertEquals(emptyList(), offending)
    }

    @Test
    fun theDocsKitImportsOnlyTheKitsItDependsOn() {
        // Arrange
        val dir = listOf("src/jvmAndroidMain/kotlin/uk/veritaslabs/kits/docs", "docs-kit/src/jvmAndroidMain/kotlin/uk/veritaslabs/kits/docs")
            .map(::File).first { it.isDirectory }
        val allowed = listOf("uk.veritaslabs.kits.docs", "uk.veritaslabs.kits.store", "uk.veritaslabs.kits.x0x", "uk.veritaslabs.kits.KitsInternalApi")

        // Act
        val offending = dir.walk().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().filter { it.startsWith("import uk.veritaslabs.kits.") }
                .filter { line -> allowed.none { line.removePrefix("import ").startsWith(it) } }
                .map { "${file.name}: $it" }
        }.toList()

        // Assert
        assertEquals(emptyList(), offending)
    }
}
