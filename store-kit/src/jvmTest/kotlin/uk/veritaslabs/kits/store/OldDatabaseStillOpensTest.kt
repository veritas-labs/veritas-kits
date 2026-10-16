package uk.veritaslabs.kits.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.async.coroutines.awaitAsList
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `vesta-v20.db` is a version-20 database written by Vesta before the kits extraction (never regenerated). It must open
 * through the kit's open path at version 20 and give back its data. The same file is exercised from Vesta against the
 * published artifact; this is the kit's own copy of that guarantee.
 */
class OldDatabaseStillOpensTest {

    private fun fixtureCopy(): File {
        val source = checkNotNull(javaClass.getResourceAsStream("/vesta-v20.db")) { "fixture missing" }
        val copy = File.createTempFile("old-install", ".db").apply { deleteOnExit() }
        source.use { input -> copy.outputStream().use { input.copyTo(it) } }
        return copy
    }

    private fun userVersion(file: File): Long = BundledSQLiteDriver().open(file.path).let { connection ->
        try {
            connection.prepare("PRAGMA user_version").let { s -> try { s.step(); s.getLong(0) } finally { s.close() } }
        } finally {
            connection.close()
        }
    }

    @Test
    fun theOldDatabaseOpensAtVersionTwentyWithItsHomeMembersAndDocuments() = runBlocking {
        // Arrange
        val file = fixtureCopy()
        val before = userVersion(file)

        // Act
        val (database, driver) = openAsTheAppDoes(file)
        val homes = database.homeQueries.selectAll().awaitAsList()
        val members = database.homeMemberQueries.selectByHome(homes.single().id).awaitAsList()
        val documents = database.automergeDocumentQueries.selectByHome(homes.single().id).awaitAsList()
        driver.close()

        // Assert
        assertEquals(20L, before)
        assertEquals(20L, KitsDatabase.Schema.version)
        assertEquals(20L, userVersion(file))
        assertEquals(listOf("The Johnsons"), homes.map { it.name })
        assertTrue(members.any { it.name == "Sam" })
        assertEquals(10, documents.size)
    }

    @Test
    fun anOlderDatabaseThanTheSchemaIsBackedUpBesideItBeforeItIsMigrated() = runBlocking {
        // Arrange
        val file = fixtureCopy()
        BundledSQLiteDriver().open(file.path).let { c -> try { c.prepare("PRAGMA user_version = 19").let { it.step(); it.close() } } finally { c.close() } }

        // Act
        val (_, driver) = openAsTheAppDoes(file)
        driver.close()

        // Assert
        val backups = file.absoluteFile.parentFile.listFiles { f -> f.name.startsWith("${file.name}.pre-v19-") }.orEmpty()
        backups.forEach { it.delete() }
        assertEquals(1, backups.size)
    }
}
