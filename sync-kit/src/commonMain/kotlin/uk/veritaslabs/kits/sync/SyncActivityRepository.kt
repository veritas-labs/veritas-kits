package uk.veritaslabs.kits.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Reads what the Sync activity screen shows (docs/adr/ADR-033-sync-activity.md). Implemented where the sync service lives;
 * [UnavailableSyncActivityRepository] stands in elsewhere.
 */
public interface SyncActivityRepository {
    /** False for the stand-in: there is no sync service behind it. */
    public val available: Boolean

    /** The Space's current facts, emitted again whenever they change (a round starts or ends, a peer comes or goes, a message is queued). */
    public fun snapshot(spaceId: String): Flow<SyncSnapshot>

    /**
     * One page of the Space's log, newest first, already collapsed. [beforeLastAt] (exclusive) continues after a previous page, and
     * [peerAgent], when set, keeps only that device's entries. Reads only the last 7 days.
     */
    public suspend fun events(spaceId: String, filter: SyncEventFilter, peerAgent: String?, beforeLastAt: Long?, limit: Int): List<SyncEventItem>

    /** Asks for a sync round now. Returns at once. */
    public fun syncNow()
}

/** The default where there is no sync service: the daemon reads as unreachable and the log is empty. */
public object UnavailableSyncActivityRepository : SyncActivityRepository {
    override val available: Boolean = false

    override fun snapshot(spaceId: String): Flow<SyncSnapshot> = flowOf(SyncSnapshot(takenAt = 0L, daemonReachable = false))

    override suspend fun events(spaceId: String, filter: SyncEventFilter, peerAgent: String?, beforeLastAt: Long?, limit: Int): List<SyncEventItem> = emptyList()

    override fun syncNow(): Unit = Unit
}
