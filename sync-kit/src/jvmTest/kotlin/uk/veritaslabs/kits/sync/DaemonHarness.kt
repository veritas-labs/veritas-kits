package uk.veritaslabs.kits.sync

import uk.veritaslabs.kits.docs.spaceDocumentId
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.veritaslabs.kits.store.testing.openInMemoryDatabase
import uk.veritaslabs.kits.spaces.HubResult
import uk.veritaslabs.kits.spaces.ProfileRepository
import uk.veritaslabs.kits.x0x.AbstractX0xClient

/**
 * What the opt-in real-daemon tests share ([HubDaemonIntegrationTest], [SeveralHubsDaemonIntegrationTest]): named x0xd daemons
 * started and stopped through the `x0x` command, and "phones" that run the real [DeviceDataSyncService] over one with their own
 * in-memory database. Use instance names nothing else uses, never a daemon in use.
 */

/** A named x0xd, started and stopped through the `x0x` command. */
internal class Daemon(val name: String) {
    private val dir = File(System.getProperty("user.home"), ".local/share/x0x-$name")

    val client = object : AbstractX0xClient() {
        override val endpoint: String get() = dir.path

        // Read each time: a restarted daemon may listen on another port.
        override suspend fun resolveDaemon(): Pair<String, String?>? {
            val port = dir.resolve("api.port").takeIf { it.exists() }?.readText()?.trim()?.substringAfterLast(':') ?: return null
            return "http://127.0.0.1:$port" to dir.resolve("api-token").takeIf { it.exists() }?.readText()?.trim()
        }
    }

    /** The daemon's own output, kept so a daemon that doesn't come up can be explained. */
    private val log = File(System.getProperty("java.io.tmpdir"), "vesta-hub-it-$name.log")
    private var process: Process? = null

    suspend fun start() {
        if (client.health() != null) return
        withContext(Dispatchers.IO) {
            process = ProcessBuilder("x0xd", "--name", name)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
        }
        until("$name to answer (its output is in $log)", seconds = 90) { client.health() != null }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching { ProcessBuilder("x0x", "stop", "--name", name).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor(30, TimeUnit.SECONDS) }
            process?.let { if (!it.waitFor(30, TimeUnit.SECONDS)) it.destroy() }
            process = null
        }
        until("$name to stop", seconds = 90) { client.health() == null }
    }

    /**
     * Stops the daemon and starts it again. x0xd needs a while to release its sockets: started sooner, it exits with
     * "shutdown socket release timeout" and leaves no `api.port` (ADR-020, Spike 8).
     */
    suspend fun restart() {
        stop()
        delay(20.seconds)
        start()
    }
}

/** A phone: the real data sync over a daemon, with its own database. */
internal class Phone(val daemon: Daemon, private val scope: CoroutineScope, private val withPairing: Boolean = false) {
    val database = openInMemoryDatabase()
    val devices = SyncedDeviceRepository(database)
    val sources = TestSources(database)
    val sync = DeviceDataSyncService(daemon.client, database, devices, ::getDeviceName, sources.documentTypes(), interval = 3.seconds, seeding = TestSeeding(sources, database))
    val homes = spaceRepository(database, sync.spaceChanges)
    val profiles = ProfileRepository(database, deviceName = ::getDeviceName, changes = sync.profileChanges)
    private var running: Job? = null
    private var pairing: Job? = null

    /** The pairing service, wired to the Home sync as `App` does; it runs beside the sync when the phone is made [withPairing]. */
    val deviceSync: DeviceSyncService by lazy {
        DeviceSyncService(
            daemon.client, database, devices, deviceName = { daemon.name },
            onDeviceUnpaired = sync::deviceUnpaired, onDevicePaired = sync::devicePaired,
        )
    }

    /** Starts the daemon and the sync, as opening the app does. */
    suspend fun open() {
        daemon.start()
        running = scope.launch { sync.run() }
        if (withPairing) pairing = scope.launch { deviceSync.run() }
    }

    /** Stops only the sync, as closing Vesta does on a desktop whose x0xd keeps running. */
    fun closeApp() {
        running?.cancel()
        running = null
        pairing?.cancel()
        pairing = null
    }

    /** Stops the sync and the daemon, as closing the app does. */
    suspend fun close() {
        pairing?.cancel()
        pairing = null
        running?.cancel()
        running = null
        daemon.stop()
    }

    suspend fun agentId() = daemon.client.agent().agentId!!

    suspend fun joinedHome(id: String) {
        database.homeQueries.insert(id, "Maple Street", "family", 1L)
        TestSeeding(sources, database).placeholdersForArrived(id)
    }

    suspend fun group(homeId: String) = database.homeGroupQueries.selectByHome(homeId).awaitAsOneOrNull()
    suspend fun titles(homeId: String) = sources.pantry.titles(homeId)

    suspend fun add(homeId: String, title: String) =
        sources.pantry.forSpace(homeId)!!.add(title)

    /** Whether everything in the Pantry is in the mailbox. */
    suspend fun deposited(homeId: String): Boolean {
        val heads = sources.pantry.forSpace(homeId)!!.heads()
        return database.mailboxQueries.selectHeads(homeId, spaceDocumentId(homeId, "pantry")).awaitAsOneOrNull()?.contentEquals(heads) == true
    }
}

/** Polls [condition] every two seconds until it holds, or fails after [seconds]. */
internal suspend fun until(what: String, seconds: Int = 180, seen: suspend () -> String = { "" }, condition: suspend () -> Boolean) {
    var waited = 0
    while (!runCatching { condition() }.getOrDefault(false)) {
        check(waited++ < seconds / 2) { "timed out waiting for $what" + runCatching { seen() }.getOrNull()?.let { " (saw: $it)" }.orEmpty() }
        delay(2.seconds)
    }
}

/** What an admin does: runs the printed commands on the hub machine, then chooses the agent that joined. */
internal suspend fun enrolHub(admin: Phone, hub: Daemon, homeId: String, hubId: String) {
    until("the enrolment commands to be ready") { admin.sync.hubs.enrolment(homeId) != null }
    val enrolment = admin.sync.hubs.enrolment(homeId)!!
    val invite = enrolment.commands.lines().first().removePrefix("x0x group join ")
    hub.client.joinGroup(invite)
    until("the hub to be active in the group") {
        hub.client.getGroupMembers(enrolment.groupId).any { it.agentId == hubId && it.state == "active" }
    }
    hub.client.openGroupStore(enrolment.groupId, "mailbox")
    until(
        "the hub to be offered as a candidate",
        seen = {
            val members = admin.daemon.client.getGroupMembers(enrolment.groupId).joinToString { "${it.agentId.take(6)}:${it.state}:${it.role}" }
            "hub ${hubId.take(6)}, candidates ${admin.sync.hubs.candidates(homeId).map { it.take(6) }}, recorded ${admin.sync.hubs.hubs(homeId).map { it.agentId.take(6) }}, group members [$members]"
        },
    ) { admin.sync.hubs.candidates(homeId) == listOf(hubId) }
    assertEquals(HubResult.Done, admin.sync.hubs.useAsHub(homeId, hubId))
}

/**
 * B is closed; A (open) adds [title] and waits until it is in the mailbox and on every one of the [hubs], then is closed; B is
 * opened and must get [title] with A down, which only a hub can give it. Leaves B open and A closed.
 */
internal suspend fun leaveOneItemAndComeBack(a: Phone, b: Phone, hubs: List<Daemon>, aId: String, homeId: String, title: String) {
    b.close()
    a.add(homeId, title)
    until("A to deposit $title") { a.deposited(homeId) }
    val store = a.group(homeId)!!.storeId!!
    hubs.forEach { hub -> until("${hub.name} to hold A's entries") { hub.client.listStoreKeys(store).any { it.key.startsWith("$aId/") } } }
    a.close()

    b.open()
    until("B to read $title from a hub, with A down", seconds = 300) { title in b.titles(homeId) }
}
