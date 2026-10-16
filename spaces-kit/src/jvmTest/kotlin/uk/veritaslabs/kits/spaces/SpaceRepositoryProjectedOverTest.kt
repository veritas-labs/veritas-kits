package uk.veritaslabs.kits.spaces

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import uk.veritaslabs.kits.store.KitsDatabase

/**
 * A Space's row is a projection of the registry, and a registry message from another device is projected over it whenever one
 * arrives, including between a user's edit and the moment the edit is reported. A trigger stands for that message: it puts the
 * old name back as soon as the new one is written.
 */
class SpaceRepositoryProjectedOverTest {

    private val saved = mutableListOf<StoredSpace>()
    private val listener = object : SpaceChangeListener {
        override suspend fun saved(space: StoredSpace) { saved += space }
        override suspend fun deleted(id: String) = Unit
    }

    private suspend fun databaseWhereTheOldNameComesBack(): KitsDatabase {
        val driver = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.Memory,
            schema = KitsDatabase.Schema,
            configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
        )
        val database = KitsDatabase(driver)
        driver.execute(
            null,
            "CREATE TRIGGER oldNameComesBack AFTER UPDATE OF name ON Home WHEN NEW.name = 'Oak Avenue' " +
                "BEGIN UPDATE Home SET name = 'Maple Street' WHERE id = NEW.id; END",
            0,
        ).await()
        return database
    }

    @Test
    fun anEditIsReportedAsTheUserMadeItEvenIfTheRegistrysOldNameWasProjectedOverItFirst() = runBlocking {
        // Arrange
        val database = databaseWhereTheOldNameComesBack()
        val repository = SpaceRepository(database, listener, ProfileRepository(database, deviceName = ::getDeviceName))
        val id = repository.create("Maple Street", "family")
        saved.clear()

        // Act
        repository.update(id, "Oak Avenue", "family")

        // Assert
        assertEquals(listOf("Oak Avenue"), saved.map { it.name })
    }
}
