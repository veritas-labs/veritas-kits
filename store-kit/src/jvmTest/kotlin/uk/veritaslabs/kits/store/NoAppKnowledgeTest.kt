package uk.veritaslabs.kits.store

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No kit's main source knows the first app. The kits take every app choice (feature ids, brand, schemes, group names,
 * file names, send order) from the app, so a literal that names one of Vesta's features, or puts "Vesta" in a string,
 * means a default crept back in. Comments and KDoc may mention the app as an example; code may not.
 */
class NoAppKnowledgeTest {

    private val featureIds = listOf("pantry", "shopping-list", "cookbook", "merchants", "mealplan", "calendar", "private-calendar", "tasks", "private-tasks")

    /** The documented exceptions: the JNI names of the native library and the generated database package are not choices of the kits. */
    private val allowedBrandLiterals = listOf("uk.veritaslabs.vesta", "uk_veritaslabs_vesta")

    private fun repositoryRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun mainSources(): List<File> =
        repositoryRoot().listFiles { f -> f.isDirectory && f.name.endsWith("-kit") }.orEmpty()
            .flatMap { kit -> File(kit, "src").listFiles { f -> f.isDirectory && f.name.endsWith("Main") }.orEmpty().toList() }
            .flatMap { dir -> dir.walk().filter { it.extension == "kt" }.toList() }

    /** The code of a file, without line comments and KDoc/block comment lines. */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        var inBlock = false
        return file.readLines().mapIndexedNotNull { index, raw ->
            var line = raw.trim()
            if (inBlock) {
                if ("*/" in line) { inBlock = false; line = line.substringAfter("*/").trim() } else return@mapIndexedNotNull null
            }
            if (line.startsWith("/*")) {
                if ("*/" !in line) inBlock = true
                return@mapIndexedNotNull null
            }
            line = line.substringBefore(" // ").takeUnless { line.startsWith("//") } ?: return@mapIndexedNotNull null
            (index + 1) to line
        }
    }

    @Test
    fun noKitMainSourceNamesAFeatureOfTheFirstAppInAStringLiteral() {
        // Arrange
        val files = mainSources()
        val literal = Regex("\"(${featureIds.joinToString("|")})\"")

        // Act
        val offending = files.flatMap { file -> codeLines(file).filter { (_, line) -> literal.containsMatchIn(line) }.map { (n, line) -> "${file.name}:$n: $line" } }

        // Assert
        assertTrue(files.size > 50, "the scan found only ${files.size} files")
        assertEquals(emptyList(), offending)
    }

    @Test
    fun noKitMainSourceHasTheAppsBrandInAStringLiteral() {
        // Arrange
        val files = mainSources()
        val literal = Regex("\"[^\"]*vesta[^\"]*\"", RegexOption.IGNORE_CASE)

        // Act
        val offending = files.flatMap { file ->
            codeLines(file)
                .filter { (_, line) -> literal.findAll(line).any { m -> allowedBrandLiterals.none { it in m.value } } }
                .map { (n, line) -> "${file.name}:$n: $line" }
        }

        // Assert
        assertEquals(emptyList(), offending)
    }
}
