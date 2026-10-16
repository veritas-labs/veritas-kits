package uk.veritaslabs.kits.spaces

import kotlin.test.Test
import kotlin.test.assertEquals
import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.runBlocking
import uk.veritaslabs.kits.store.KitsDatabase
import uk.veritaslabs.kits.store.testing.openInMemoryDatabase

class SpaceRostersRenameTest {

    /**
     * A rename writes the Profile and the member row, then tells the listener. A roster message that arrives in between is projected
     * over the row ([SpaceRosters.project]), which puts the roster's old name back; `onChanged` stands for that message.
     */
    private suspend fun aHomeWhoseListenerRunsFirst(onChanged: suspend (SpaceRosters) -> Unit): Triple<KitsDatabase, SpaceRosters, ProfileRepository> {
        val database = openInMemoryDatabase()
        lateinit var rosters: SpaceRosters
        val listener = object : ProfileChangeListener {
            override suspend fun changed() {
                onChanged(rosters)
                rosters.publish("home-1", "agent-me", pairedAgents = emptySet())
            }
        }
        val profiles = ProfileRepository(database, deviceName = ::getDeviceName, changes = listener)
        rosters = SpaceRosters(database, profiles, deviceName = ::getDeviceName)
        database.homeQueries.insert("home-1", "Home", "family", 1L)
        val me = profiles.current().id
        rosters.admit("home-1", me, getDeviceName(), "admin", "agent-me")
        return Triple(database, rosters, profiles)
    }

    @Test
    fun aRenameIsPublishedEvenWhenARosterMessageIsProjectedOverTheMemberRowFirst() = runBlocking {
        // Arrange
        val (database, rosters, profiles) = aHomeWhoseListenerRunsFirst { it.project("home-1") }

        // Act
        profiles.rename("Xavier")

        // Assert
        assertEquals(listOf("Xavier"), rosters.members("home-1").map { it.name })
        assertEquals(listOf("Xavier"), database.homeMemberQueries.selectByHome("home-1").awaitAsList().map { it.name })
    }

    @Test
    fun aNamePlaceholderDoesNotUndoANameAnotherDeviceHasPublished() = runBlocking {
        // Arrange: the roster already carries the name the person chose on their other device, and this device has only the placeholder
        val database = openInMemoryDatabase()
        val profiles = ProfileRepository(database, deviceName = ::getDeviceName)
        val rosters = SpaceRosters(database, profiles, deviceName = ::getDeviceName)
        database.homeQueries.insert("home-1", "Home", "family", 1L)
        val me = profiles.current().id
        rosters.admit("home-1", me, "Xavier", "admin", "agent-me")

        // Act
        rosters.publish("home-1", "agent-me", pairedAgents = emptySet())

        // Assert
        assertEquals(listOf("Xavier"), rosters.members("home-1").map { it.name })
    }
}
