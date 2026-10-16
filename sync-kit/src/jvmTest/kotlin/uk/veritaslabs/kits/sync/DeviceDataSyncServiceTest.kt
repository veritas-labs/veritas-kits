package uk.veritaslabs.kits.sync

import uk.veritaslabs.kits.docs.spaceDocumentId
import uk.veritaslabs.kits.docs.SyncEnvelope
import uk.veritaslabs.kits.docs.SyncEventRecorder
import uk.veritaslabs.kits.docs.SyncModules
import uk.veritaslabs.kits.spaces.SpaceRepository
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.encoding.Base64
import uk.veritaslabs.kits.store.KitsDatabase
import uk.veritaslabs.kits.store.testing.openInMemoryDatabase
import uk.veritaslabs.kits.x0x.testing.FakeX0xNetwork
import uk.veritaslabs.kits.spaces.MemberRepository
import uk.veritaslabs.kits.spaces.RoleChangeResult
import uk.veritaslabs.kits.spaces.MemberRole
import uk.veritaslabs.kits.spaces.LeaveResult
import uk.veritaslabs.kits.spaces.RemoveMemberResult
import uk.veritaslabs.kits.spaces.SpaceJoinMessage
import uk.veritaslabs.kits.spaces.ProfileRepository
import org.automerge.SyncState
import uk.veritaslabs.kits.spaces.SpaceRegistryDocumentId
import uk.veritaslabs.kits.spaces.rosterDocumentId
import uk.veritaslabs.kits.x0x.X0xApiException
import uk.veritaslabs.kits.x0x.X0xClient
import uk.veritaslabs.kits.x0x.X0xDirectMessage
import uk.veritaslabs.kits.spaces.SpaceRegistry
import uk.veritaslabs.kits.spaces.SpaceJoinService
import uk.veritaslabs.kits.spaces.SpaceRosters

/** A daemon that cannot open group stores, as a daemon that is too old to. */
private class WithoutGroupStores(private val node: X0xClient) : X0xClient by node {
    override suspend fun openGroupStore(groupId: String, name: String): String = throw X0xApiException(404, "store not found")
}

class DeviceDataSyncServiceTest {

    private val network = FakeX0xNetwork()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Stops every device the test started, also when it failed before its own cancel, so no sync outlives the test and fails in another. */
    @AfterTest
    fun stopEveryDevice() {
        scope.cancel()
    }

    /** One of the user's devices: its own x0x node and database, with a running data sync. */
    private inner class Device(
        val agentId: String,
        interval: Duration = 50.milliseconds,
        peerBackoff: Duration = 2.minutes,
        /** Gives the device a Sync activity recorder that fails every time it is asked for the time, to show recording never matters to a sync. */
        brokenRecorder: Boolean = false,
        /**
         * False gives the device a daemon that cannot open group stores (as one that is too old does), so it has no mailbox and everything it
         * syncs goes by direct message. The tests of what Sync activity logs for a direct sync need that: with a mailbox, which of the two
         * paths delivers an edit first is a race, and only the direct path logs a received row in the document's module.
         */
        mailbox: Boolean = true,
    ) {
        val node = network.node(agentId)
        val database: KitsDatabase = openInMemoryDatabase()
        val devices = SyncedDeviceRepository(database)
        val sources = TestSources(database)
        /** The id of every document a sync message has reached this device for. */
        val receivedDocuments = ConcurrentLinkedQueue<String>()
        val sync = DeviceDataSyncService(
            if (mailbox) node else WithoutGroupStores(node), database, devices, ::getDeviceName, sources.documentTypes(), interval = interval,
            spacesConfig = TestSpacesConfig, seeding = TestSeeding(sources, database), sendOrder = TestSendOrder,
            peerBackoff = peerBackoff,
            recorder = if (brokenRecorder) SyncEventRecorder(database, nowMillis = { error("the clock is broken") }) else SyncEventRecorder(database),
        )
        /** The pairing service, for the tests that unpair a device; it only handles messages once [routeDeviceSyncMessages] is set. */
        val deviceSync: DeviceSyncService by lazy {
            DeviceSyncService(
                client = node, database = database, devices = devices, deviceName = { agentId }, config = TestDeviceSyncConfig,
                pollInterval = 1.milliseconds, joinTimeout = 5.seconds, ownViewTimeout = 20.milliseconds,
                resendInterval = 5.milliseconds, removeRetryDelay = 1.milliseconds,
                onDeviceUnpaired = sync::deviceUnpaired, onDevicePaired = sync::devicePaired,
            )
        }
        var routeDeviceSyncMessages = false
        val homes = spaceRepository(database, sync.spaceChanges)
        val profiles = ProfileRepository(database, deviceName = ::getDeviceName, changes = sync.profileChanges)


        init {
            node.onDirect = { message: X0xDirectMessage ->
                message.payload?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }?.let { SyncEnvelope.decode(it) }
                    ?.let { receivedDocuments.add(it.documentId) }
                scope.launch {
                    // As the engine's own message loop does: a message that fails (a Home deleted while its member row was being projected, say)
                    // is dropped and the next round recovers it, and must not escape as an uncaught exception into whichever test runs next.
                    try {
                        if (routeDeviceSyncMessages) deviceSync.handle(message)
                        sync.handle(message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun start() {
            scope.launch { sync.run() }
        }

        suspend fun pairedWith(other: Device) = apply { devices.add(other.agentId, other.agentId) }

        /** Gives this device a copy of a Home another person made, as joining it will: the Home row and an empty Pantry to fill. */
        suspend fun joinedHome(id: String, name: String, kind: String) {
            database.homeQueries.insert(id, name, kind, 1L)
            TestSeeding(sources, database).placeholdersForArrived(id)
        }

        suspend fun activity(homeId: String) = database.syncEventQueries.selectPage(homeId, Long.MAX_VALUE, 100).awaitAsList()

        suspend fun profileIds() = database.profileQueries.selectAll().awaitAsList().map { it.id }

        suspend fun roleOf(homeId: String, memberId: String) =
            database.homeMemberQueries.selectByHome(homeId).awaitAsList().firstOrNull { it.memberId == memberId }?.role

        suspend fun memberIds(homeId: String) =
            database.homeMemberQueries.selectByHome(homeId).awaitAsList().map { it.memberId }

        suspend fun homeNames() = database.homeQueries.selectAll().awaitAsList().map { it.name }.sorted()

        suspend fun shoppingTitles(homeId: String) = sources.shopping.titles(homeId)

        suspend fun pantryTitles(homeId: String) = sources.pantry.titles(homeId)
    }

    private suspend fun pair(): Pair<Device, Device> {
        val a = Device("agent-a")
        val b = Device("agent-b")
        a.pairedWith(b)
        b.pairedWith(a)
        return a to b
    }

    private suspend fun eventually(check: suspend () -> Boolean) {
        withTimeout(10.seconds) { while (!check()) delay(20.milliseconds) }
    }

    @Test
    fun aPeerThatHasSyncedReportsWhenItLastDidAndAStrangerDoesNot() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        b.start()
        val homeId = a.homes.create("Maple Street", "family")
        eventually { b.homeNames() == listOf("Maple Street") }
        eventually { a.sync.agentDetails(homeId, "agent-b").name != null } // b's roster entry reaches a a round later

        // Act
        val synced = a.sync.agentDetails(homeId, "agent-b")
        val stranger = a.sync.agentDetails(homeId, "agent-z")

        // Assert
        assertTrue(synced.lastSyncedMsAgo != null && synced.lastSyncedMsAgo!! < 10_000)
        assertTrue(synced.name != null) // learnt from the roster, which agent-b wrote
        assertEquals(null, stranger.name)
        assertEquals(null, stranger.lastSyncedMsAgo)
        assertEquals(null, stranger.machineId)
        assertEquals(null, stranger.lastSeenMsAgo)
        scope.cancel()
    }

    @Test
    fun aHomeCreatedOnOneDeviceAppearsOnTheOther() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        b.start()

        // Act
        val id = a.homes.create("Maple Street", "family")

        // Assert
        eventually { b.homeNames() == listOf("Maple Street") }
        assertEquals(id, b.database.homeQueries.selectAll().awaitAsList().single().id)
        scope.cancel()
    }

    @Test
    fun aHomeThatExistedBeforePairingIsSyncedOnceDevicesAreRunning() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.homes.create("Old Home", "flatshare")

        // Act
        a.start()
        b.start()

        // Assert
        eventually { b.homeNames() == listOf("Old Home") }
        scope.cancel()
    }

    @Test
    fun pantryItemsAddedOnEitherDeviceReachBoth() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        b.start()
        val homeId = a.homes.create("Maple Street", "family")
        eventually { b.homeNames().isNotEmpty() }

        // Act
        a.sources.pantry.forSpace(homeId)!!.add("Whole Milk")
        eventually { b.pantryTitles(homeId) == listOf("Whole Milk") }
        b.sources.pantry.forSpace(homeId)!!.add("Beef Mince")

        // Assert
        eventually { a.pantryTitles(homeId) == listOf("Beef Mince", "Whole Milk") }
        assertEquals(listOf("Beef Mince", "Whole Milk"), b.pantryTitles(homeId))
        scope.cancel()
    }






    @Test
    fun aPrivateDocumentReachesTheUsersOwnDevicesAndNeverAnotherMemberOfTheSpace() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        val x2 = Device("agent-x2")
        x.pairedWith(x2)
        x2.pairedWith(x)
        x.start()
        x2.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 }

        // Act
        x.sources.privateCalendar.forSpace(homeId)!!.add("Surprise party planning")
        x.sources.calendar.forSpace(homeId)!!.add("Bin day")
        eventually { x2.sources.privateCalendar.titles(homeId) == listOf("Surprise party planning") }
        eventually { y.sources.calendar.titles(homeId) == listOf("Bin day") }
        delay(500.milliseconds) // long enough for several sync rounds to have passed

        // Assert
        assertTrue(spaceDocumentId(homeId, "private-calendar") !in y.receivedDocuments)
        assertEquals(emptyList(), y.sources.privateCalendar.titles(homeId))
        assertTrue(spaceDocumentId(homeId, "calendar") in y.receivedDocuments)
        scope.cancel()
    }






    @Test
    fun renamingAndDeletingAHomeReachesTheOtherDevice() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        b.start()
        val id = a.homes.create("Maple Street", "family")
        eventually { b.homeNames() == listOf("Maple Street") }

        // Act
        b.homes.update(id, "Oak Avenue", "family")
        eventually { a.homeNames() == listOf("Oak Avenue") }
        a.homes.delete(id)

        // Assert
        eventually { b.homeNames().isEmpty() }
        assertTrue(b.database.automergeDocumentQueries.selectByHome(id).awaitAsList().isEmpty())
        scope.cancel()
    }

    @Test
    fun aDeviceThatIsNotPairedIsIgnored() = runBlocking {
        // Arrange
        val a = Device("agent-a")
        val stranger = Device("agent-stranger")
        stranger.pairedWith(a)
        a.start()
        stranger.start()

        // Act
        stranger.homes.create("Not Yours", "other")
        delay(500.milliseconds)

        // Assert
        assertEquals(emptyList(), a.homeNames())
        scope.cancel()
    }

    @Test
    fun aDeviceThatComesOnlineIsSyncedAtOnceNotAtTheNextRound() = runBlocking {
        // Arrange: the timer is an hour, so only the presence event can deliver the Home in time
        val a = Device("agent-a", interval = 1.hours)
        val b = Device("agent-b", interval = 1.hours)
        a.pairedWith(b)
        b.pairedWith(a)
        network.offline += b.agentId
        a.start()
        b.start()
        delay(300.milliseconds) // both have run their first round and opened their presence streams
        a.homes.create("Maple Street", "family")
        delay(300.milliseconds)
        assertTrue(b.homeNames().isEmpty())

        // Act
        network.comeOnline(b.agentId)

        // Assert
        eventually { b.homeNames() == listOf("Maple Street") }
        scope.cancel()
    }

    @Test
    fun aSyncRequestedWhenTheAppReturnsToTheForegroundRunsARoundNow() = runBlocking {
        // Arrange
        val a = Device("agent-a", interval = 1.hours)
        val b = Device("agent-b", interval = 1.hours)
        a.pairedWith(b)
        b.pairedWith(a)
        network.offline += b.agentId
        a.start()
        b.start()
        delay(300.milliseconds)
        a.homes.create("Maple Street", "family")
        delay(300.milliseconds)
        network.offline -= b.agentId // back, but no presence event arrives

        // Act
        a.sync.syncNow()

        // Assert
        eventually { b.homeNames() == listOf("Maple Street") }
        scope.cancel()
    }

    @Test
    fun aDeviceThatWasOfflineCatchesUpWhenItReturns() = runBlocking {
        // Arrange
        val (a, b) = pair()
        network.offline += b.agentId
        a.start()
        b.start()

        // Act
        a.homes.create("While Away", "family")
        delay(300.milliseconds)
        network.offline -= b.agentId

        // Assert
        eventually { b.homeNames() == listOf("While Away") }
        scope.cancel()
    }

    @Test
    fun pairedDevicesSettleOnTheOlderDevicesProfileAndMoveTheirMembershipsToIt() = runBlocking {
        // Arrange
        val (a, b) = pair()
        network.offline += b.agentId // b is not reachable until it starts, so it makes its own Profile first
        a.start()
        eventually { a.profileIds().isNotEmpty() }
        val aProfile = a.profileIds().single()
        delay(20.milliseconds) // so b's Profile is unambiguously the younger
        val bHome = b.homes.create("Made On B", "other")
        val bProfileBefore = b.profileIds().single()

        // Act
        network.offline -= b.agentId
        b.start()

        // Assert
        eventually { b.profileIds() == listOf(aProfile) }
        eventually { b.memberIds(bHome) == listOf(aProfile) }
        assertEquals(listOf(aProfile), a.profileIds())
        assertTrue(bProfileBefore != aProfile)
        scope.cancel()
    }

    @Test
    fun aMembersDevicesAreOneMemberOnBothDevicesAndBothAreInTheirRoster() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        val homeId = a.homes.create("Maple Street", "family")
        val aProfile = a.profileIds().single()
        delay(20.milliseconds)

        // Act
        b.start()

        // Assert
        eventually { b.memberIds(homeId) == listOf(aProfile) }
        eventually { a.sync.rosterDevices(homeId) == mapOf(aProfile to setOf("agent-a", "agent-b")) }
        assertEquals(listOf(aProfile), a.memberIds(homeId))
        scope.cancel()
    }

    @Test
    fun aHomeMadeBeforeASecondDeviceTookOnTheSharedProfileEndsUpWithOneMember() = runBlocking {
        // Arrange
        val (a, b) = pair()
        network.offline += b.agentId
        a.start()
        eventually { a.profileIds().isNotEmpty() }
        val aProfile = a.profileIds().single()
        delay(20.milliseconds)
        val bHome = b.homes.create("Made On B", "other")

        // Act
        network.offline -= b.agentId
        b.start()

        // Assert
        eventually { a.homeNames() == listOf("Made On B") }
        eventually { a.memberIds(bHome) == listOf(aProfile) && b.memberIds(bHome) == listOf(aProfile) }
        eventually { b.sync.rosterDevices(bHome) == mapOf(aProfile to setOf("agent-a", "agent-b")) }
        scope.cancel()
    }

    @Test
    fun removingADeviceDropsItFromTheRosterButTheMemberStays() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        val homeId = a.homes.create("Maple Street", "family")
        val aProfile = a.profileIds().single()
        delay(20.milliseconds)
        b.start()
        eventually { a.sync.rosterDevices(homeId) == mapOf(aProfile to setOf("agent-a", "agent-b")) }

        // Act
        a.devices.remove(b.agentId)

        // Assert
        eventually { a.sync.rosterDevices(homeId) == mapOf(aProfile to setOf("agent-a")) }
        assertEquals(listOf(aProfile), a.memberIds(homeId))
        scope.cancel()
    }

    /** Two people who are not paired: X made the Home and admitted Y; Y has the Home and knows X invited it. */
    private suspend fun twoPeopleInOneHome(suffix: String = "", mailbox: Boolean = true): Triple<Device, Device, String> {
        val x = Device("agent-x$suffix", mailbox = mailbox)
        val y = Device("agent-y$suffix", mailbox = mailbox)
        val homeId = x.homes.create("Maple Street", "family")
        val xMember = x.profiles.current()
        val yMember = y.profiles.current()
        y.joinedHome(homeId, "Maple Street", "family")
        x.sync.admit(homeId, yMember.id, yMember.displayName, "member", y.agentId)
        y.sync.admit(homeId, xMember.id, xMember.displayName, "admin", x.agentId)
        return Triple(x, y, homeId)
    }

    @Test
    fun membersOfAHomeWhoAreNotPairedShareItsShoppingList() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()

        // Act
        x.sources.shopping.forSpace(homeId)!!.add("Whole Milk")
        eventually { y.shoppingTitles(homeId) == listOf("Whole Milk") }
        y.sources.shopping.forSpace(homeId)!!.add("Beef Mince")

        // Assert
        eventually { x.shoppingTitles(homeId) == listOf("Beef Mince", "Whole Milk") }
        scope.cancel()
    }

    @Test
    fun membersOfAHomeWhoAreNotPairedSyncItsPantry() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()

        // Act
        x.sources.pantry.forSpace(homeId)!!.add("Whole Milk")
        eventually { y.pantryTitles(homeId) == listOf("Whole Milk") }
        y.sources.pantry.forSpace(homeId)!!.add("Beef Mince")

        // Assert
        eventually { x.pantryTitles(homeId) == listOf("Beef Mince", "Whole Milk") }
        scope.cancel()
    }

    @Test
    fun aHomeSyncsItsMembersButNotTheRestOfEachOthersData() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()

        // Act
        val private = x.homes.create("X Only", "other")
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        delay(300.milliseconds)

        // Assert
        assertEquals(listOf("Maple Street"), y.homeNames())
        assertEquals(2, x.homeNames().size)
        assertTrue(private !in y.database.homeQueries.selectAll().awaitAsList().map { it.id })
        scope.cancel()
    }

    @Test
    fun theRosterShowsBothPeopleOnBothDevices() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()

        // Act
        x.start()
        y.start()

        // Assert
        eventually { x.memberIds(homeId).size == 2 && y.memberIds(homeId).size == 2 }
        assertEquals(x.memberIds(homeId).sorted(), y.memberIds(homeId).sorted())
        scope.cancel()
    }

    @Test
    fun aRemovedMemberNoLongerSyncsTheHome() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        x.sources.pantry.forSpace(homeId)!!.add("Whole Milk")
        eventually { y.pantryTitles(homeId) == listOf("Whole Milk") }
        val yMember = y.profiles.current().id

        // Act
        x.sync.removeMember(homeId, yMember)
        y.sources.pantry.forSpace(homeId)!!.add("Beef Mince")
        delay(500.milliseconds)

        // Assert
        assertEquals(listOf("Whole Milk"), x.pantryTitles(homeId))
        assertEquals(listOf(x.profiles.current().id), x.memberIds(homeId))
        scope.cancel()
    }

    @Test
    fun aDeviceThatIsNotInTheRosterCannotSyncAHome() = runBlocking {
        // Arrange
        val (x, _, homeId) = twoPeopleInOneHome()
        val stranger = Device("agent-stranger")
        stranger.joinedHome(homeId, "Maple Street", "family")
        x.start()
        stranger.start()

        // Act
        stranger.sources.pantry.forSpace(homeId)!!.add("Beef Mince")
        delay(500.milliseconds)

        // Assert
        assertEquals(emptyList(), x.pantryTitles(homeId))
        scope.cancel()
    }

    @Test
    fun aPersonWhoOpensAnInviteLinkIsApprovedIntoAHomeAndSyncsIt() = runBlocking {
        // Arrange
        val x = Device("agent-x")
        val y = Device("agent-y")
        val homeId = x.homes.create("Maple Street", "family")
        x.sources.pantry.forSpace(homeId)!!.add("Whole Milk")
        x.start()
        y.start()
        val link = x.sync.joining.createInviteLink(homeId)!!

        // Act
        y.sync.joining.redeemInvite(link)
        eventually { x.sync.joining.joinRequests(homeId).isNotEmpty() }
        x.sync.joining.approve(homeId, y.profiles.current().id)

        // Assert
        eventually { y.homeNames() == listOf("Maple Street") }
        eventually { x.memberIds(homeId).size == 2 && y.memberIds(homeId).size == 2 }
        assertEquals(x.memberIds(homeId).sorted(), y.memberIds(homeId).sorted())
        eventually { y.pantryTitles(homeId) == listOf("Whole Milk") }
        scope.cancel()
    }

    @Test
    fun aJoinerAnswersAgainWhenTheInviterRepeatsAnInvitationItAlreadyAccepted() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val xMember = x.profiles.current()
        val invite = SpaceJoinMessage.encode(SpaceJoinMessage.Invite(homeId, "Maple Street", "family", 1L, xMember.id, xMember.displayName))
        y.node.sentTo.clear()

        // Act
        x.node.sendDirect(y.agentId, invite)

        // Assert
        eventually { x.agentId in y.node.sentTo }
        scope.cancel()
    }

    @Test
    fun anAdminsPromotionOfAMemberReachesTheMembersOwnDevice() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val members = MemberRepository(x.database, x.profiles, x.sync.rosterChanges)

        // Act
        val result = members.setRole(homeId, y.profiles.current().id, MemberRole.Admin)

        // Assert
        assertEquals(RoleChangeResult.Changed, result)
        eventually { y.roleOf(homeId, y.profiles.current().id) == "admin" }
        assertEquals("admin", x.roleOf(homeId, y.profiles.current().id))
        scope.cancel()
    }

    @Test
    fun aMemberRemovedByAnAdminLosesTheHomeOnTheirDevice() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val members = MemberRepository(x.database, x.profiles, x.sync.rosterChanges)

        // Act
        val result = members.removeMember(homeId, y.profiles.current().id)

        // Assert
        assertEquals(RemoveMemberResult.Removed, result)
        eventually { y.homeNames().isEmpty() }
        assertEquals(listOf(x.profiles.current().id), x.memberIds(homeId))
        assertEquals(mapOf(x.profiles.current().id to setOf("agent-x")), x.sync.rosterDevices(homeId))
        scope.cancel()
    }

    @Test
    fun aMemberWhoLeavesIsDroppedFromTheOthersRoster() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val leaver = SpaceRepository(y.database, y.sync.spaceChanges, y.profiles, y.sync.rosterChanges)

        // Act
        val result = leaver.leave(homeId)

        // Assert
        assertEquals(LeaveResult.Left, result)
        eventually { x.memberIds(homeId) == listOf(x.profiles.current().id) }
        assertEquals(emptyList(), y.homeNames())
        scope.cancel()
    }

    @Test
    fun aMemberRemovedWhileTheirDeviceIsOfflineLosesTheHomeWhenItIsBack() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val members = MemberRepository(x.database, x.profiles, x.sync.rosterChanges)
        network.offline += y.agentId

        // Act
        members.removeMember(homeId, y.profiles.current().id)
        delay(300.milliseconds)
        assertEquals(listOf("Maple Street"), y.homeNames())
        network.offline -= y.agentId

        // Assert
        eventually { y.homeNames().isEmpty() }
        eventually { x.database.pendingMessageQueries.selectAll().awaitAsList().isEmpty() }
        scope.cancel()
    }

    @Test
    fun aDepartureIsToldToADeviceThatWasOfflineWhenTheMemberLeft() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val leaver = SpaceRepository(y.database, y.sync.spaceChanges, y.profiles, y.sync.rosterChanges)
        network.offline += x.agentId

        // Act
        leaver.leave(homeId)
        delay(300.milliseconds)
        assertEquals(2, x.memberIds(homeId).size)
        network.offline -= x.agentId

        // Assert
        eventually { x.memberIds(homeId) == listOf(x.profiles.current().id) }
        eventually { y.database.pendingMessageQueries.selectAll().awaitAsList().isEmpty() }
        scope.cancel()
    }

    @Test
    fun aMessageNobodyAcknowledgesIsGivenUpOnAfterItsLifetime() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        var now = 1_000L
        val join = SpaceJoinService(
            x.node, x.database, SpaceRosters(x.database, x.profiles, deviceName = ::getDeviceName), x.sync.spaceChanges, x.profiles, TestSeeding(x.sources, x.database),
            config = TestSpacesConfig, nowMillis = { now }, messageLifetime = 10.seconds,
        )
        network.offline += y.agentId
        join.removeMember(homeId, y.profiles.current().id)

        // Act
        now += 5_000L
        join.resendPending()
        val before = x.database.pendingMessageQueries.selectAll().awaitAsList().size
        now += 6_000L
        join.resendPending()

        // Assert
        assertEquals(1, before)
        assertEquals(0, x.database.pendingMessageQueries.selectAll().awaitAsList().size)
        scope.cancel()
    }

    @Test
    fun anOrdinaryMemberCannotRemoveAnotherMemberBySendingTheMessageThemselves() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }

        // Act: Y is an ordinary member, and says X has been removed from Home
        y.node.sendDirect(x.agentId, SpaceJoinMessage.encode(SpaceJoinMessage.Removed(homeId)))
        delay(300.milliseconds)

        // Assert
        assertEquals(listOf("Maple Street"), x.homeNames())
        scope.cancel()
    }

    @Test
    fun aMemberCannotBeDroppedByADeviceThatIsNotTheirs() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }

        // Act: Y claims X has left
        y.node.sendDirect(x.agentId, SpaceJoinMessage.encode(SpaceJoinMessage.Left(homeId, x.profiles.current().id)))
        delay(300.milliseconds)

        // Assert
        assertEquals(2, x.memberIds(homeId).size)
        scope.cancel()
    }

    @Test
    fun aNameChosenOnOneDeviceReachesTheirOtherDevice() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        eventually { a.profileIds().isNotEmpty() }
        delay(20.milliseconds)
        b.start()
        eventually { b.profileIds() == a.profileIds() }

        // Act
        a.profiles.rename("Mark")

        // Assert
        eventually { b.profiles.current().displayName == "Mark" && b.profiles.current().named }
        scope.cancel()
    }

    @Test
    fun aNameChosenBeforePairingBeatsTheOlderDevicesPlaceholder() = runBlocking {
        // Arrange
        val (a, b) = pair()
        network.offline += b.agentId
        a.start()
        eventually { a.profileIds().isNotEmpty() }
        delay(20.milliseconds)
        b.profiles.rename("Mark")
        val bProfile = b.profileIds().single()

        // Act
        network.offline -= b.agentId
        b.start()

        // Assert
        eventually { a.profileIds() == listOf(bProfile) }
        assertEquals("Mark", a.profiles.current().displayName)
        assertEquals(listOf(bProfile), b.profileIds())
        scope.cancel()
    }

    @Test
    fun aMembersNewNameReachesTheOtherMembersOfAHome() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val xMember = x.profiles.current().id

        // Act
        x.profiles.rename("Xavier")

        // Assert
        eventually { y.database.homeMemberQueries.selectByHome(homeId).awaitAsList().first { it.memberId == xMember }.name == "Xavier" }
        scope.cancel()
    }

    // --- The mailbox (ADR-020) ---

    /** Waits until both devices of [homeId] are in the Home's group with its mailbox store open, then adds an always-on hub to it. */
    private suspend fun withHub(x: Device, y: Device, homeId: String, hubName: String = "agent-hub"): String {
        eventually { x.mailboxStore(homeId) != null && y.mailboxStore(homeId) != null }
        val groupId = x.database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()!!.groupId
        val hub = network.node(hubName)
        hub.joinGroup(x.node.createGroupInvite(groupId))
        hub.openGroupStore(groupId, "mailbox")
        return groupId
    }

    private suspend fun Device.mailboxStore(homeId: String) = database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()?.storeId

    /** Whether everything this device has in [homeId]'s Pantry is already in the mailbox. */
    private suspend fun Device.pantryDeposited(homeId: String): Boolean {
        val heads = sources.pantry.forSpace(homeId)!!.heads()
        return database.mailboxQueries.selectHeads(homeId, spaceDocumentId(homeId, "pantry")).awaitAsOneOrNull()?.contentEquals(heads) == true
    }

    /** Whether everything this device has in [homeId]'s shopping list is already in the mailbox. */
    private suspend fun Device.shoppingDeposited(homeId: String): Boolean {
        val heads = sources.shopping.forSpace(homeId)!!.heads()
        return database.mailboxQueries.selectHeads(homeId, spaceDocumentId(homeId, "shopping-list")).awaitAsOneOrNull()?.contentEquals(heads) == true
    }

    @Test
    fun twoMembersWhoAreNeverOnlineTogetherStillSyncThroughTheHub() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        withHub(x, y, homeId)
        network.offline += y.agentId

        // Act: X edits while Y is away, and is itself gone by the time Y is back, so no direct message can carry it
        x.sources.shopping.forSpace(homeId)!!.add("Whole Milk")
        eventually { x.shoppingDeposited(homeId) }
        network.offline += x.agentId
        network.comeOnline(y.agentId)

        // Assert
        eventually { y.shoppingTitles(homeId) == listOf("Whole Milk") }
        scope.cancel()
    }

    @Test
    fun aChangeMadeWhileTheOtherMemberIsAwayReachesThemWhenTheirAppIsOpenedAgain() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        withHub(x, y, homeId)
        network.offline += x.agentId

        // Act
        y.sources.pantry.forSpace(homeId)!!.add("Beef Mince")
        eventually { y.pantryDeposited(homeId) && y.pantryTitles(homeId) == listOf("Beef Mince") }
        network.offline += y.agentId
        network.comeOnline(x.agentId)
        x.sync.syncNow()

        // Assert
        eventually { x.pantryTitles(homeId) == listOf("Beef Mince") }
        scope.cancel()
    }

    @Test
    fun aMemberWithNoGroupYetIsUnaffectedAndStillSyncsByDirectMessage() = runBlocking {
        // Arrange: the creator is not running, so nobody can invite Y into a group
        val (x, y, homeId) = twoPeopleInOneHome()
        y.start()
        x.start()
        network.offline += x.agentId

        // Act
        y.sources.pantry.forSpace(homeId)!!.add("Beef Mince")
        delay(300.milliseconds)
        network.comeOnline(x.agentId)

        // Assert: the direct-message path still delivers it
        eventually { x.pantryTitles(homeId) == listOf("Beef Mince") }
        scope.cancel()
    }

    // --- Peers that do not answer (ADR-020 by-hand run: an offline peer delayed the mailbox by minutes) ---

    /** Waits until [check] holds, but for no longer than [within]; false if it never did. */
    private suspend fun within(within: Duration, check: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(within) { while (!check()) delay(20.milliseconds); true } ?: false

    @Test
    fun aChangeIsDepositedWithoutWaitingForAPeerThatDoesNotAnswer() = runBlocking {
        // Arrange: Y's path has gone stale, so every direct message to it waits before failing
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        withHub(x, y, homeId)
        network.hangFor = 3.seconds
        network.hanging += y.agentId

        // Act
        x.sources.pantry.forSpace(homeId)!!.add("Beef Mince")

        // Assert: the mailbox has it long before one send to Y has timed out
        assertTrue(within(1500.milliseconds) { x.pantryDeposited(homeId) })
        scope.cancel()
    }

    @Test
    fun aPeerThatDidNotAnswerIsTriedOnceNotOncePerDocument() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        network.hangFor = 100.milliseconds
        network.hanging += y.agentId

        // Act: one full round, which has the Home's Pantry, shopping list and roster to send
        x.sync.syncEverything()
        val first = x.node.sentTo.count { it == y.agentId }
        x.sync.syncEverything()

        // Assert: one failed send is enough to leave Y alone for a while, in this round and the next
        assertEquals(1, first)
        assertEquals(1, x.node.sentTo.count { it == y.agentId })
        scope.cancel()
    }

    @Test
    fun aPeerThatCameBackIsSyncedAtOnceEvenAfterItWasGivenUpOn() = runBlocking {
        // Arrange: X gives up on Y after a failed send, and then edits
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        network.hangFor = 100.milliseconds
        network.hanging += y.agentId
        x.sync.syncEverything()
        x.sources.pantry.forSpace(homeId)!!.add("Beef Mince")

        // Act: Y comes back; X sees it come online
        network.hanging -= y.agentId
        network.comeOnline(y.agentId)

        // Assert
        assertTrue(within(5.seconds) { y.pantryTitles(homeId) == listOf("Beef Mince") })
        scope.cancel()
    }

    @Test
    fun aDeviceThatIsOpenedReadsTheMailboxWithoutWaitingForPeersThatDoNotAnswer() = runBlocking {
        // Arrange: X leaves a change in the mailbox and is gone; Y is then opened, with X's path stale
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        withHub(x, y, homeId)
        network.offline += y.agentId
        x.sources.pantry.forSpace(homeId)!!.add("Beef Mince")
        eventually { x.pantryDeposited(homeId) }
        network.hangFor = 3.seconds
        network.hanging += x.agentId
        network.offline += x.agentId
        y.sync.syncNow()

        // Act
        network.comeOnline(y.agentId)

        // Assert: Y reads it from the hub well before a send to X could have timed out
        assertTrue(within(1500.milliseconds) { y.pantryTitles(homeId) == listOf("Beef Mince") })
        scope.cancel()
    }

    // --- Unpairing a device (docs/plans/unpaired-device-gap-plan.md, slice 3) ---

    @Test
    fun unpairingADeviceLeavesItOutOfTheRosterOfEveryHomeAndPairingItAgainBringsItBack() = runBlocking {
        // Arrange: X's Home lists a second device of X's
        val x = Device("agent-x")
        val homeId = x.homes.create("Maple Street", "family")
        x.sync.admit(homeId, x.profiles.current().id, "X", "admin", "agent-x")
        x.sync.admit(homeId, x.profiles.current().id, "X", "admin", "agent-x-tablet")
        val before = x.sync.rosterDevices(homeId).values.single()

        // Act
        x.sync.deviceUnpaired("agent-x-tablet")
        val whileUnpaired = x.sync.rosterDevices(homeId).values.single()
        x.sync.devicePaired("agent-x-tablet")
        val afterPairing = x.sync.rosterDevices(homeId).values.single()

        // Assert
        assertEquals(setOf("agent-x", "agent-x-tablet"), before)
        assertEquals(setOf("agent-x"), whileUnpaired)
        assertEquals(setOf("agent-x", "agent-x-tablet"), afterPairing)
        scope.cancel()
    }

    @Test
    fun unpairingADeviceFromThePairingScreenTakesItOutOfTheHomesGroup() = runBlocking {
        // Arrange: two devices of one user, paired for real, sharing a Home whose group both are in
        val first = Device("agent-1")
        val second = Device("agent-2")
        first.routeDeviceSyncMessages = true
        second.routeDeviceSyncMessages = true
        val link = PairingLink(agentId = second.agentId, expiresAt = System.currentTimeMillis() + 60_000, deviceName = "Second")
        second.database.devicePairingLinkQueries.insert(link.agentId, link.agentId, System.currentTimeMillis(), link.expiresAt)
        assertTrue(first.deviceSync.pairWith(link).isSuccess)
        eventually { second.devices.agentIds().contains(first.agentId) }
        first.start()
        second.start()
        val homeId = first.homes.create("Maple Street", "family")
        eventually { second.homeNames() == listOf("Maple Street") && first.profiles.current().id == second.profiles.current().id }
        // Both devices may make a group for the Home at first, and the one the roster names wins; wait until they are in the same one
        eventually {
            val firstGroup = first.database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()
            val secondGroup = second.database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()
            firstGroup?.storeId != null && secondGroup?.storeId != null && firstGroup.groupId == secondGroup.groupId &&
                "agent-2" in network.groups.getValue(firstGroup.groupId).members.keys
        }

        // Act: the user removes the second device on the first
        val result = first.deviceSync.removeDevice("agent-2")

        // Assert: the Home's group lets it go, not only the pairing list
        assertTrue(result.isSuccess)
        // The Home's group is the one the first device is in now: the same group, or a new one if the second device had made it
        eventually {
            val groupId = first.database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()?.groupId
            groupId != null && "agent-2" !in network.groups.getValue(groupId).members.keys
        }
        val groupId = first.database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()!!.groupId
        assertTrue("agent-1" in network.groups.getValue(groupId).members.keys)
        scope.cancel()
    }

    @Test
    fun aTasksEditIsLoggedAsSentOnTheEditorAndReceivedOnTheOtherMember() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome(mailbox = false)
        x.start()
        y.start()

        // Act
        x.sources.tasks.forSpace(homeId)!!.add("Bins")
        eventually { y.sources.tasks.titles(homeId) == listOf("Bins") }
        eventually { y.activity(homeId).any { it.kind == "received" } && x.activity(homeId).any { it.kind == "sent" } }

        // Assert
        val received = y.activity(homeId).first { it.kind == "received" && it.module == "tasks" }
        assertEquals("agent-x", received.peerAgent)
        val sent = x.activity(homeId).first { it.kind == "sent" && it.module == "tasks" }
        assertEquals("agent-y", sent.peerAgent)
        scope.cancel()
    }

    @Test
    fun anUnchangedRoundIsOnlyAHandshakeAndAddsNoReceivedEvent() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome(mailbox = false)
        x.start()
        y.start()
        x.sources.tasks.forSpace(homeId)!!.add("Bins")
        eventually { y.sources.tasks.titles(homeId) == listOf("Bins") }
        eventually { y.activity(homeId).any { it.kind == "received" && it.module == "tasks" } }
        delay(300.milliseconds) // let the changes and the replies to them settle
        val before = y.activity(homeId).filter { it.kind == "received" }.sumOf { it.count }

        // Act
        delay(600.milliseconds) // several more full rounds, each restarting the handshake, with nothing new to send
        val after = y.activity(homeId).filter { it.kind == "received" }.sumOf { it.count }

        // Assert
        assertEquals(before, after)
        scope.cancel()
    }

    @Test
    fun anOfflinePeerIsLoggedAsOneUnreachableRowThatCountsEachFailedRound() = runBlocking {
        // Arrange
        val a = Device("agent-a", peerBackoff = 1.milliseconds)
        val b = Device("agent-b")
        a.pairedWith(b)
        b.pairedWith(a)
        network.hanging += b.agentId // a direct message to it fails after hangFor; to an offline one it vanishes without an error
        network.hangFor = 20.milliseconds
        a.start()
        val homeId = a.homes.create("Maple Street", "family")

        // Act
        eventually { a.activity(homeId).any { it.kind == "failed" && it.module == "pantry" && it.count >= 2 } }

        // Assert
        val failed = a.activity(homeId).filter { it.kind == "failed" && it.module == "pantry" }
        assertEquals("agent-b", failed.single().peerAgent)
        assertEquals("unreachable", failed.single().detail)
        scope.cancel()
    }

    @Test
    fun aMemberLeavingIsLoggedAsAMembershipEventOnTheMemberWhoStays() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val leaver = SpaceRepository(y.database, y.sync.spaceChanges, y.profiles, y.sync.rosterChanges)

        // Act
        leaver.leave(homeId)
        eventually { x.memberIds(homeId) == listOf(x.profiles.current().id) }
        eventually { x.activity(homeId).any { it.kind == "membership" && it.detail == "left" } }

        // Assert
        val left = x.activity(homeId).first { it.kind == "membership" && it.detail == "left" }
        assertEquals("agent-y", left.peerAgent)
        assertEquals("members", left.module)
        scope.cancel()
    }

    @Test
    fun aRemovedMemberAndTheirAcknowledgementAreLoggedAsMembershipEventsOnTheAdmin() = runBlocking {
        // Arrange
        val (x, y, homeId) = twoPeopleInOneHome()
        x.start()
        y.start()
        eventually { y.memberIds(homeId).size == 2 && x.memberIds(homeId).size == 2 }
        val members = MemberRepository(x.database, x.profiles, x.sync.rosterChanges)

        // Act
        members.removeMember(homeId, y.profiles.current().id)
        eventually { x.activity(homeId).any { it.kind == "membership" && it.detail == "acknowledged" } }

        // Assert: the removal and the acknowledgement of it are separate rows for the same device
        val rows = x.activity(homeId).filter { it.kind == "membership" }
        assertEquals(setOf("agent-y"), rows.mapNotNull { it.peerAgent }.toSet())
        assertEquals(setOf("removed", "acknowledged"), rows.mapNotNull { it.detail }.toSet())
        assertEquals(listOf(1L, 1L), rows.map { it.count })
        scope.cancel()
    }

    @Test
    fun theTimeAPeerLastSyncedSurvivesAFreshServiceOnTheSameDatabase() = runBlocking {
        // Arrange
        val (a, b) = pair()
        a.start()
        b.start()
        val homeId = a.homes.create("Maple Street", "family")
        eventually { b.homeNames() == listOf("Maple Street") }
        eventually { a.database.peerSyncQueries.selectByAgent("agent-b").awaitAsOneOrNull() != null }

        // Act
        val fresh = DeviceDataSyncService(a.node, a.database, a.devices, ::getDeviceName, a.sources.documentTypes(), spacesConfig = TestSpacesConfig, seeding = TestSeeding(a.sources, a.database), sendOrder = TestSendOrder) // never started: no in-memory times
        val details = fresh.agentDetails(homeId, "agent-b")

        // Assert
        assertTrue(details.lastSyncedMsAgo != null)
        scope.cancel()
    }

    @Test
    fun aRecorderThatFailsEveryTimeLeavesTheSyncUnchanged() = runBlocking {
        // Arrange
        val a = Device("agent-a", brokenRecorder = true)
        val b = Device("agent-b", brokenRecorder = true)
        a.pairedWith(b)
        b.pairedWith(a)
        a.start()
        b.start()
        val homeId = a.homes.create("Maple Street", "family")
        eventually { b.homeNames() == listOf("Maple Street") }

        // Act
        a.sources.pantry.forSpace(homeId)!!.add("Whole Milk")
        eventually { b.pantryTitles(homeId) == listOf("Whole Milk") }

        // Assert
        assertEquals(emptyList(), b.activity(homeId))
        assertTrue(a.sync.agentDetails(homeId, "agent-b").lastSyncedMsAgo != null)
        scope.cancel()
    }

    // --- Every Home document, one test per behaviour (docs/plans/document-registry-and-source-collapse-plan.md, WP0) ---

    /** One kind of Home document: how to edit it on a device, how to see that edit on another, and where it is named. */
    private inner class DocumentCase(
        val name: String,
        val idFor: (String) -> String,
        val module: String,
        val private: Boolean,
        val edit: suspend Device.(homeId: String) -> Unit,
        val seen: suspend Device.(homeId: String) -> Boolean,
        val heads: suspend Device.(homeId: String) -> ByteArray?,
        /** The document's side of a sync exchange on a device, null when the device has no such document. */
        val peer: suspend Device.(homeId: String) -> SyncPeer?,
        /** Gives a device with a bare Home row the document, for those that don't exist until something writes them. */
        val prime: suspend Device.(homeId: String) -> Unit = {},
    )

    private class SyncPeer(val generate: suspend (SyncState) -> ByteArray?, val receive: suspend (SyncState, ByteArray) -> Unit)

    /** A case over one of the engine's test document types: a note is the edit, and the note arriving is what is seen. */
    private fun noteCase(feature: String, module: String, private: Boolean, source: TestSources.() -> NoteSources) = DocumentCase(
        feature, { spaceDocumentId(it, feature) }, module, private,
        edit = { sources.source().forSpace(it)!!.add("note in $feature") },
        seen = { sources.source().titles(it) == listOf("note in $feature") },
        heads = { sources.source().forSpace(it)?.heads() },
        peer = { sources.source().forSpace(it)?.let { s -> SyncPeer(s::generateSync, s::receiveSync) } },
    )

    private val documentCases = listOf(
        noteCase("pantry", TestModules.Pantry, private = false) { pantry },
        noteCase("shopping-list", TestModules.Shopping, private = false) { shopping },
        noteCase("cookbook", TestModules.Cookbook, private = false) { cookbook },
        noteCase("merchants", TestModules.Merchants, private = false) { merchants },
        noteCase("mealplan", TestModules.MealPlan, private = false) { mealPlan },
        noteCase("calendar", TestModules.Calendar, private = false) { calendar },
        noteCase("private-calendar", TestModules.Calendar, private = true) { privateCalendar },
        noteCase("tasks", TestModules.Tasks, private = false) { tasks },
        noteCase("private-tasks", TestModules.Tasks, private = true) { privateTasks },
        DocumentCase(
            "roster", ::rosterDocumentId, SyncModules.Members, private = false,
            edit = { profiles.rename("Xavier") },
            seen = { memberNames(it).contains("Xavier") },
            heads = { SpaceRosters(database, profiles, deviceName = ::getDeviceName).heads(it) },
            peer = {
                val rosters = SpaceRosters(database, profiles, deviceName = ::getDeviceName)
                rosters.heads(it)?.let { _ -> SyncPeer({ state -> rosters.generate(it, state) }, { state, message -> rosters.receive(it, state, message) }) }
            },
            prime = { SpaceRosters(database, profiles, deviceName = ::getDeviceName).publish(it, agentId, emptySet()) },
        ),
    )

    private fun directMessage(sender: String, documentId: String, message: ByteArray) = X0xDirectMessage(
        sender = sender, payload = Base64.Default.encode(SyncEnvelope(documentId, message).encode()), verified = true, trustDecision = "accept",
    )

    private suspend fun Device.memberNames(homeId: String) =
        database.homeMemberQueries.selectByHome(homeId).awaitAsList().map { it.name }

    /** Whether everything this device has of [case]'s document in [homeId] is already in the mailbox. */
    private suspend fun Device.deposited(case: DocumentCase, homeId: String): Boolean {
        val heads = case.heads(this, homeId) ?: return false
        return database.mailboxQueries.selectHeads(homeId, case.idFor(homeId)).awaitAsOneOrNull()?.contentEquals(heads) == true
    }

    private suspend fun Device.eventCount(homeId: String, kind: String, module: String) =
        activity(homeId).filter { it.kind == kind && it.module == module }.sumOf { it.count }

    /** [eventually], but a check that throws counts as not yet true, and a timeout names the document and what never happened. */
    private suspend fun eventuallyFor(case: DocumentCase, what: String, check: suspend () -> Boolean) {
        try {
            eventually { runCatching { check() }.getOrDefault(false) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("${case.name}: $what never happened", e)
        }
    }

    /** Each case gets its own scope, so one case's devices stop before the next case's start. */
    private fun freshScope() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Test
    fun everyDocumentCatchesUpAMemberWhoWasAwayThroughTheMailbox() = runBlocking {
        documentCases.filterNot { it.private }.forEachIndexed { index, case ->
            // Arrange: private documents are not in the mailbox, so only the shared ones are here
            freshScope()
            val (x, y, homeId) = twoPeopleInOneHome("-mailbox$index")
            x.start()
            y.start()
            withHub(x, y, homeId, "agent-hub-mailbox$index")
            network.offline += y.agentId

            // Act: X edits while Y is away, and is gone by the time Y is back, so no direct message can carry it
            case.edit(x, homeId)
            eventuallyFor(case, "deposit in the mailbox") { x.deposited(case, homeId) }
            network.offline += x.agentId
            network.comeOnline(y.agentId)

            // Assert
            eventuallyFor(case, "catching up through the mailbox") { case.seen(y, homeId) }
        }
        scope.cancel()
    }

    @Test
    fun everySharedDocumentThatArrivesBeforeItsHomeIsKeptAndAppliedWhenTheHomeArrives() = runBlocking {
        documentCases.filterNot { it.private }.forEachIndexed { index, case ->
            // Arrange: neither A nor B is running; every message is built and handed to B by hand. A message that carries changes
            // can't come from a real round before B has the Home (B never replies to a document it can't open), so A's document
            // and A's Home registry are each primed with the "have" of an empty replica on a third device first.
            freshScope()
            val a = Device("agent-a-waiting$index")
            val b = Device("agent-b-waiting$index")
            b.pairedWith(a)
            val homeId = a.homes.create("Maple Street", "family")
            case.edit(a, homeId)
            val replica = Device("agent-e-waiting$index")
            replica.database.homeQueries.insert(homeId, "Maple Street", "family", 1L) // a bare Home row: its documents start empty, with no history in common with A's
            replica.sources.pantry.forSpace(homeId) // the shopping list needs the Pantry to exist
            case.prime(replica, homeId)
            val aDocument = case.peer(a, homeId)!!
            val aState = SyncState()
            aDocument.receive(aState, case.peer(replica, homeId)!!.generate(SyncState())!!)
            val documentMessages = mutableListOf<ByteArray>()
            while (true) documentMessages += aDocument.generate(aState) ?: break
            val aRegistry = SpaceRegistry(a.database, TestSeeding(a.sources, a.database))
            val registryState = SyncState()
            aRegistry.receive(registryState, SpaceRegistry(replica.database, TestSeeding(replica.sources, replica.database)).generate(SyncState())!!)
            val registryMessages = mutableListOf<ByteArray>()
            while (true) registryMessages += aRegistry.generate(registryState) ?: break
            val sender = a.agentId
            documentMessages.forEach { b.sync.handle(directMessage(sender, case.idFor(homeId), it)) }
            val homesBeforeRegistry = b.homeNames()

            // Act
            registryMessages.forEach { b.sync.handle(directMessage(sender, SpaceRegistryDocumentId, it)) }

            // Assert
            assertTrue(documentMessages.isNotEmpty() && registryMessages.isNotEmpty(), "${case.name}: nothing to hand to B")
            assertEquals(emptyList(), homesBeforeRegistry, "${case.name}: B had the Home before the registry arrived")
            assertEquals(listOf("Maple Street"), b.homeNames())
            eventuallyFor(case, "being applied once the Home arrived") { case.seen(b, homeId) }
        }
        scope.cancel()
    }

    @Test
    fun everySharedDocumentThatArrivesBeforeItsHomeIsStillKeptWhenAnotherRegistryMessageComesFirst() = runBlocking {
        documentCases.filterNot { it.private }.forEachIndexed { index, case ->
            // Arrange: neither A nor B is running; every message is built and handed to B by hand. A message that carries changes
            // can't come from a real round before B has the Home (B never replies to a document it can't open), so A's document
            // and A's Home registry are each primed with the "have" of an empty replica on a third device first.
            freshScope()
            val a = Device("agent-a-waiting$index")
            val b = Device("agent-b-waiting$index")
            b.pairedWith(a)
            val homeId = a.homes.create("Maple Street", "family")
            case.edit(a, homeId)
            val replica = Device("agent-e-waiting$index")
            replica.database.homeQueries.insert(homeId, "Maple Street", "family", 1L) // a bare Home row: its documents start empty, with no history in common with A's
            replica.sources.pantry.forSpace(homeId) // the shopping list needs the Pantry to exist
            case.prime(replica, homeId)
            val aDocument = case.peer(a, homeId)!!
            val aState = SyncState()
            aDocument.receive(aState, case.peer(replica, homeId)!!.generate(SyncState())!!)
            val documentMessages = mutableListOf<ByteArray>()
            while (true) documentMessages += aDocument.generate(aState) ?: break
            val aRegistry = SpaceRegistry(a.database, TestSeeding(a.sources, a.database))
            val registryState = SyncState()
            aRegistry.receive(registryState, SpaceRegistry(replica.database, TestSeeding(replica.sources, replica.database)).generate(SyncState())!!)
            val registryMessages = mutableListOf<ByteArray>()
            while (true) registryMessages += aRegistry.generate(registryState) ?: break
            val sender = a.agentId
            documentMessages.forEach { b.sync.handle(directMessage(sender, case.idFor(homeId), it)) }
            val homesBeforeRegistry = b.homeNames()

            // Act: the registry message that carries the Home is not the first to follow the documents; an empty replica's handshake comes between
            val handshake = SpaceRegistry(replica.database, TestSeeding(replica.sources, replica.database)).generate(SyncState())!!
            b.sync.handle(directMessage(sender, SpaceRegistryDocumentId, handshake))
            val homesAfterHandshake = b.homeNames()
            registryMessages.forEach { b.sync.handle(directMessage(sender, SpaceRegistryDocumentId, it)) }

            // Assert
            assertTrue(documentMessages.isNotEmpty() && registryMessages.isNotEmpty(), "${case.name}: nothing to hand to B")
            assertEquals(emptyList(), homesBeforeRegistry, "${case.name}: B had the Home before the registry arrived")
            assertEquals(emptyList(), homesAfterHandshake, "${case.name}: the handshake carried the Home")
            assertEquals(listOf("Maple Street"), b.homeNames())
            eventuallyFor(case, "being applied once the Home arrived") { case.seen(b, homeId) }
        }
        scope.cancel()
    }

    @Test
    fun everyDocumentsEditIsLoggedInSyncActivityUnderItsModule() = runBlocking {
        documentCases.forEachIndexed { index, case ->
            // Arrange: X and Y share a Home, and X2 is X's own second device, which is who a private document goes to
            freshScope()
            val (x, y, homeId) = twoPeopleInOneHome("-activity$index", mailbox = false)
            val x2 = Device("agent-x2-activity$index")
            x.pairedWith(x2)
            x2.pairedWith(x)
            x.start()
            x2.start()
            y.start()
            eventuallyFor(case, "everyone having the Home") { y.memberIds(homeId).size == 2 && x2.homeNames().isNotEmpty() }
            delay(400.milliseconds) // let the first exchanges settle, so only this case's edit adds to its module
            val observer = if (case.private) x2 else y
            val receivedBefore = observer.eventCount(homeId, "received", case.module)

            // Act
            case.edit(x, homeId)
            eventuallyFor(case, "the edit reaching the other device") { case.seen(observer, homeId) }

            // Assert: a sent row is not proof, as a handshake is logged as sent too; a received row is, as it needs the document to change
            eventuallyFor(case, "a received event in ${case.module}") { observer.eventCount(homeId, "received", case.module) > receivedBefore }
            eventuallyFor(case, "a sent event in ${case.module}") { x.eventCount(homeId, "sent", case.module) > 0 }
        }
        scope.cancel()
    }

    @Test
    fun theEngineSyncsAHomesDocumentsInOneFixedOrderWithTheRosterLast() = runBlocking {
        // Arrange: documentIds() is private, so its order shows in the order a round's first messages reach a peer
        val (a, b) = pair()
        val homeId = a.homes.create("Maple Street", "family")

        // Act
        a.sync.syncEverything()

        // Assert
        val order = b.receivedDocuments.filter { it.startsWith("$homeId:") }.distinct()
        assertEquals(
            listOf(
                "$homeId:pantry", "$homeId:shopping-list", "$homeId:cookbook", "$homeId:merchants", "$homeId:mealplan",
                "$homeId:calendar", "$homeId:private-calendar", "$homeId:tasks", "$homeId:private-tasks", "$homeId:roster",
            ),
            order,
        )
        scope.cancel()
    }
}
