package uk.veritaslabs.kits.sync

import uk.veritaslabs.kits.docs.SyncEventRecorder

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uk.veritaslabs.kits.store.KitsDatabase
import uk.veritaslabs.kits.store.testing.openInMemoryDatabase
import uk.veritaslabs.kits.x0x.testing.FakeX0xNetwork
import uk.veritaslabs.kits.spaces.ProfileRepository
import uk.veritaslabs.kits.x0x.X0xClient
import uk.veritaslabs.kits.x0x.X0xDirectMessage

/**
 * One of the user's devices on the fake mesh, for the Sync activity tests: its own node and database, with a data sync that runs once
 * [start]ed. Unlike the private device of `DeviceDataSyncServiceTest` it can be given another client (to break the daemon), a clock and a
 * short refresh interval for the snapshot flow.
 */
internal class ActivityDevice(
    network: FakeX0xNetwork,
    private val scope: CoroutineScope,
    val agentId: String,
    interval: Duration = 50.milliseconds,
    activityRefresh: Duration = 20.seconds,
    peerBackoff: Duration = 2.minutes,
    nowMillis: () -> Long = { System.currentTimeMillis() },
    /** False takes the Home's mailbox away, as a daemon that can't open the group's store does: nothing is deposited and nothing fetched. */
    mailbox: Boolean = true,
    wrapClient: (X0xClient) -> X0xClient = { it },
) {
    val node = network.node(agentId)
    val database: KitsDatabase = openInMemoryDatabase()
    val devices = SyncedDeviceRepository(database)
    val sources = TestSources(database)
    val recorder = SyncEventRecorder(database, nowMillis)
    val sync = DeviceDataSyncService(
        wrapClient(
            if (mailbox) node else object : X0xClient by node {
                override suspend fun openGroupStore(groupId: String, name: String): String = error("the group store cannot be opened")
            },
        ),
        database, devices, ::getDeviceName, sources.documentTypes(), interval = interval, peerBackoff = peerBackoff, nowMillis = nowMillis, recorder = recorder,
        activityRefresh = activityRefresh,
        spacesConfig = TestSpacesConfig, seeding = TestSeeding(sources, database), sendOrder = TestSendOrder,
    )
    val homes = spaceRepository(database, sync.spaceChanges)
    val profiles = ProfileRepository(database, deviceName = ::getDeviceName, changes = sync.profileChanges)

    init {
        node.onDirect = { message: X0xDirectMessage ->
            scope.launch { sync.handle(message) }
        }
    }

    fun start() {
        scope.launch { sync.run() }
    }

    suspend fun pairedWith(other: ActivityDevice) = apply { devices.add(other.agentId, other.agentId) }

    /** Edits the Space's first document here, as the user would. */
    suspend fun addToPantry(homeId: String, title: String) {
        sources.pantry.forSpace(homeId)!!.add(title)
    }
}
