package uk.veritaslabs.kits.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uk.veritaslabs.kits.store.KitsDatabase

/**
 * Reads and writes the user's synced devices in the local [KitsDatabase]: this device's cache of the other
 * members of its DeviceSync group, which [DeviceSyncService] keeps in step with the group.
 */
public class SyncedDeviceRepository(
    private val database: KitsDatabase,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /** Every synced device, oldest first, re-emitted whenever the table changes. */
    public val devices: Flow<List<SyncedDevice>> = database.syncedDeviceQueries.selectAll()
        .asFlow()
        .map { query -> query.awaitAsList().map { SyncedDevice(name = it.name, agentId = it.agentId) } }

    /** Records the device with [agentId] as synced under [name]; adding an existing agent renames it. */
    public suspend fun add(name: String, agentId: String) {
        database.syncedDeviceQueries.insert(agentId = agentId, name = name, addedAt = nowMillis())
    }

    /** The agent ids of every synced device. */
    public suspend fun agentIds(): Set<String> =
        database.syncedDeviceQueries.selectAll().awaitAsList().mapTo(mutableSetOf()) { it.agentId }

    /** Forgets every synced device, e.g. once this device has left the group. */
    public suspend fun removeAll() {
        database.syncedDeviceQueries.deleteAll()
    }

    /** Forgets the device with [agentId]; a no-op if it isn't synced. */
    public suspend fun remove(agentId: String) {
        database.syncedDeviceQueries.deleteByAgentId(agentId)
    }
}
