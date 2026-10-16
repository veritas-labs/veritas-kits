package uk.veritaslabs.kits.spaces

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import uk.veritaslabs.kits.store.testing.openInMemoryDatabase
class SpaceRepositoryTest {

    @Test
    fun emitsEmptyWhenThereAreNoSpaces() = runTest {
        // Arrange
        val repository = spaceRepository(openInMemoryDatabase())

        // Act
        val homes = repository.spaces.first()

        // Assert
        assertEquals(emptyList(), homes)
    }

    @Test
    fun emitsStoredSpaces() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        db.homeQueries.insert("the-johnsons", "The Johnsons", "family", 1L)
        val repository = spaceRepository(db)

        // Act
        val homes = repository.spaces.first()

        // Assert
        assertEquals(listOf(StoredSpace(id = "the-johnsons", name = "The Johnsons", kind = "family", createdAt = 1L)), homes)
    }

    @Test
    fun reEmitsWhenASpaceIsInserted() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val repository = spaceRepository(db)
        val before = repository.spaces.first()

        // Act
        db.homeQueries.insert("flat-4b", "Flat 4B", "flatshare", 1L)
        val after = repository.spaces.first()

        // Assert
        assertEquals(0, before.size)
        assertEquals(listOf("Flat 4B"), after.map { it.name })
    }

    @Test
    fun createStoresTheSpaceAndReturnsItsId() = runTest {
        // Arrange
        val repository = spaceRepository(openInMemoryDatabase(), nowMillis = { 42L })

        // Act
        val id = repository.create("Flat 4B", "flatshare")

        // Assert
        assertEquals(StoredSpace(id = id, name = "Flat 4B", kind = "flatshare", createdAt = 42L), repository.get(id))
    }

    @Test
    fun createMakesTheCurrentProfileTheSpacesAdminMember() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val profiles = ProfileRepository(db, deviceName = { "Mark's laptop" })
        val repository = SpaceRepository(db, profiles = profiles, nowMillis = { 42L })

        // Act
        val id = repository.create("Flat 4B", "flatshare")

        // Assert
        val me = profiles.current()
        val members = MemberRepository(db, profiles).members(id).first()
        assertEquals(listOf(MemberListItem(me.id, "Mark's laptop", MemberRole.Admin, isYou = true)), members)
    }

    @Test
    fun getReturnsNullForAnUnknownSpace() = runTest {
        // Arrange
        val repository = spaceRepository(openInMemoryDatabase())

        // Act
        val home = repository.get("missing")

        // Assert
        assertNull(home)
    }

    @Test
    fun updateChangesTheNameAndShape() = runTest {
        // Arrange
        val repository = spaceRepository(openInMemoryDatabase())
        val id = repository.create("Flat 4B", "flatshare")

        // Act
        repository.update(id, "Family Home", "family")

        // Assert
        assertEquals(listOf("Family Home", "family"), repository.get(id)?.let { listOf(it.name, it.kind) })
    }

    @Test
    fun deleteRemovesTheSpace() = runTest {
        // Arrange
        val repository = spaceRepository(openInMemoryDatabase())
        val id = repository.create("Flat 4B", "flatshare")

        // Act
        repository.delete(id)

        // Assert
        assertNull(repository.get(id))
        assertEquals(emptyList(), repository.spaces.first())
    }

    @Test
    fun leavingAsTheOnlyMemberEndsTheSpace() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val repository = SpaceRepository(db, profiles = ProfileRepository(db, deviceName = { "Mark" }))
        val id = repository.create("Flat 4B", "flatshare")

        // Act
        val result = repository.leave(id)

        // Assert
        assertEquals(LeaveResult.Left, result)
        assertNull(repository.get(id))
    }

    @Test
    fun theOnlyAdminOfASpaceWithOtherMembersCannotLeave() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val repository = SpaceRepository(db, profiles = ProfileRepository(db, deviceName = { "Mark" }))
        val id = repository.create("Flat 4B", "flatshare")
        db.homeMemberQueries.insert(id, "sam", "Sam", null, "member", 2L)

        // Act
        val result = repository.leave(id)

        // Assert
        assertEquals(LeaveResult.OnlyAdmin, result)
        assertEquals("Flat 4B", repository.get(id)?.name)
    }

    @Test
    fun anAdminCanLeaveWhenAnotherMemberIsAlsoAnAdmin() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val repository = SpaceRepository(db, profiles = ProfileRepository(db, deviceName = { "Mark" }))
        val id = repository.create("Flat 4B", "flatshare")
        db.homeMemberQueries.insert(id, "sam", "Sam", null, "admin", 2L)

        // Act
        val result = repository.leave(id)

        // Assert
        assertEquals(LeaveResult.Left, result)
        assertNull(repository.get(id))
    }

    @Test
    fun anOrdinaryMemberCanLeave() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val profiles = ProfileRepository(db, deviceName = { "Mark" })
        val repository = SpaceRepository(db, profiles = profiles)
        val me = profiles.current().id
        db.homeQueries.insert("home-1", "Flat 4B", "flatshare", 1L)
        db.homeMemberQueries.insert("home-1", "sam", "Sam", null, "admin", 1L)
        db.homeMemberQueries.insert("home-1", me, "Mark", null, "member", 2L)

        // Act
        val result = repository.leave("home-1")

        // Assert
        assertEquals(LeaveResult.Left, result)
        assertNull(repository.get("home-1"))
    }

    @Test
    fun leavingTellsTheOtherMembersBeforeTheSpaceIsDeleted() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val profiles = ProfileRepository(db, deviceName = { "Mark" })
        val told = mutableListOf<Triple<String, String, Boolean>>()
        val listener = object : RosterChangeListener {
            override suspend fun roleChanged(homeId: String, memberId: String, role: String) = Unit
            override suspend fun memberRemoved(homeId: String, memberId: String) = Unit
            override suspend fun memberLeft(homeId: String, memberId: String) {
                told += Triple(homeId, memberId, db.homeQueries.selectById(homeId).awaitAsOneOrNull() != null)
            }
        }
        val repository = SpaceRepository(db, profiles = profiles, rosterChanges = listener)
        val id = repository.create("Flat 4B", "flatshare")

        // Act
        repository.leave(id)

        // Assert
        assertEquals(listOf(Triple(id, profiles.current().id, true)), told)
        assertNull(repository.get(id))
    }

    @Test
    fun beingRefusedLeavingTellsNoOne() = runTest {
        // Arrange
        val db = openInMemoryDatabase()
        val profiles = ProfileRepository(db, deviceName = { "Mark" })
        var told = 0
        val listener = object : RosterChangeListener {
            override suspend fun roleChanged(homeId: String, memberId: String, role: String) = Unit
            override suspend fun memberRemoved(homeId: String, memberId: String) = Unit
            override suspend fun memberLeft(homeId: String, memberId: String) { told++ }
        }
        val repository = SpaceRepository(db, profiles = profiles, rosterChanges = listener)
        val id = repository.create("Flat 4B", "flatshare")
        db.homeMemberQueries.insert(id, "sam", "Sam", null, "member", 2L)

        // Act
        val result = repository.leave(id)

        // Assert
        assertEquals(LeaveResult.OnlyAdmin, result)
        assertEquals(0, told)
    }
}

private fun spaceRepository(
    database: uk.veritaslabs.kits.store.KitsDatabase,
    nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): SpaceRepository = SpaceRepository(database, profiles = ProfileRepository(database, deviceName = ::getDeviceName), nowMillis = nowMillis)
