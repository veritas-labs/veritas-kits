package uk.veritaslabs.kits.spaces

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SpacesKitBoundaryTest {

    private fun sources(): List<File> =
        listOf("src", "spaces-kit/src").map(::File).first { it.isDirectory }
            .let { src -> listOf("commonMain", "jvmAndroidMain").map { File(src, "$it/kotlin/uk/veritaslabs/kits/spaces") } }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.extension == "kt" }.toList() }

    @Test
    fun theSpacesKitImportsNothingOfVesta() {
        // Arrange
        val files = sources()

        // Act
        val offending = files.flatMap { file ->
            // store-kit's generated rows keep the package uk.veritaslabs.vesta.db (WP15), so those imports are the store kit's
            file.readLines().filter { it.startsWith("import uk.veritaslabs.vesta.") && !it.startsWith("import uk.veritaslabs.vesta.db.") }.map { "${file.name}: $it" }
        }

        // Assert
        assertEquals(emptyList(), offending)
        assertEquals(true, files.isNotEmpty())
    }

    @Test
    fun theSpacesKitImportsOnlyTheKitsItDependsOn() {
        // Arrange
        val allowed = listOf(
            "uk.veritaslabs.kits.spaces", "uk.veritaslabs.kits.docs", "uk.veritaslabs.kits.store", "uk.veritaslabs.kits.x0x",
            "uk.veritaslabs.kits.KitsInternalApi",
        )

        // Act
        val offending = sources().flatMap { file ->
            file.readLines().filter { it.startsWith("import uk.veritaslabs.kits.") }
                .filter { line -> allowed.none { line.removePrefix("import ").startsWith(it) } }
                .map { "${file.name}: $it" }
        }

        // Assert
        assertEquals(emptyList(), offending)
    }

    @Test
    fun theCommonSourcesNeedNeitherX0xNorTheDocumentKit() {
        // Arrange
        val common = sources().filter { "commonMain" in it.invariantSeparatorsPath }

        // Act
        val offending = common.flatMap { file ->
            file.readLines().filter { it.startsWith("import uk.veritaslabs.kits.x0x") || it.startsWith("import uk.veritaslabs.kits.docs") || it.startsWith("import org.automerge") }
                .map { "${file.name}: $it" }
        }

        // Assert (iOS compiles commonMain, and neither x0x-kit nor docs-kit has an iOS target)
        assertEquals(emptyList(), offending)
    }
}
