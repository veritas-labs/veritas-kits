package uk.veritaslabs.kits.spaces

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uk.veritaslabs.kits.store.KitsDatabase

/** What happened when the current user tried to leave a Space. */
public enum class LeaveResult {
    /** They left: the Space is gone from this device, and from their other devices as they sync. */
    Left,

    /** They are the Space's only admin and other members remain, so someone else must become an admin first. */
    OnlyAdmin,
}

/**
 * Reads and writes Spaces in the local [KitsDatabase], telling [changes] about each change. A Space's [StoredSpace.kind] is
 * a string the app chooses (Vesta stores its Home shape there); the kit never interprets it.
 */
public class SpaceRepository(
    private val database: KitsDatabase,
    private val changes: SpaceChangeListener? = null,
    private val profiles: ProfileRepository,
    private val rosterChanges: RosterChangeListener? = null,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    /** Every Space, re-emitted whenever the Space table changes. */
    public val spaces: Flow<List<StoredSpace>> = database.homeQueries.selectAll()
        .asFlow()
        .map { query -> query.awaitAsList().map { StoredSpace(id = it.id, name = it.name, kind = it.shape, createdAt = it.createdAt) } }

    /** The Space with the given [id], or null if there is none. */
    public suspend fun get(id: String): StoredSpace? =
        database.homeQueries.selectById(id).awaitAsOneOrNull()?.let {
            StoredSpace(id = it.id, name = it.name, kind = it.shape, createdAt = it.createdAt)
        }

    /**
     * Creates a Space named [name] of the given [kind], returning its new id. The creator, the current Profile, is its
     * first member and its admin (docs/adr/ADR-014-home-membership.md).
     */
    public suspend fun create(name: String, kind: String): String {
        val id = Uuid.random().toString()
        val createdAt = nowMillis()
        val creator = profiles.current()
        database.homeQueries.insert(id, name, kind, createdAt)
        database.homeMemberQueries.insert(
            homeId = id,
            memberId = creator.id,
            name = creator.displayName,
            avatarResourceId = creator.avatarResourceId,
            role = MemberRole.Admin.storageValue,
            joinedAt = createdAt,
        )
        changes?.saved(StoredSpace(id, name, kind, createdAt))
        return id
    }

    /** Renames the Space with the given [id] and changes its [kind]. */
    public suspend fun update(id: String, name: String, kind: String) {
        database.homeQueries.updateHome(name, kind, id)
        // The new name and kind are reported as given, not read back: the row is only a projection of the registry, and a registry
        // message projected between the update and the read puts the old values back, which would then be reported (and so kept) as the edit.
        database.homeQueries.selectById(id).awaitAsOneOrNull()
            ?.let { changes?.saved(StoredSpace(it.id, name, kind, it.createdAt)) }
    }

    /**
     * The current user leaves the Space with the given [id], which removes it from their devices. The only admin of a Space
     * that has other members can't leave ([LeaveResult.OnlyAdmin], CORE-EXPERIENCE §5). Leaving as the only member
     * leaves nobody, so it ends the Space. The other members are told by [rosterChanges] before the Space goes
     * (docs/adr/ADR-014-home-membership.md).
     */
    public suspend fun leave(id: String): LeaveResult {
        val me = profiles.current().id
        val members = database.homeMemberQueries.selectByHome(id).awaitAsList()
        val admin = MemberRole.Admin.storageValue
        val iAmAdmin = members.any { it.memberId == me && it.role == admin }
        val otherAdmins = members.any { it.memberId != me && it.role == admin }
        if (iAmAdmin && !otherAdmins && members.any { it.memberId != me }) return LeaveResult.OnlyAdmin
        rosterChanges?.memberLeft(id, me) // before the Space, and its roster, are gone
        delete(id)
        return LeaveResult.Left
    }

    /** Deletes the Space with the given [id]; its members are removed with it. */
    public suspend fun delete(id: String) {
        database.homeQueries.deleteById(id)
        changes?.deleted(id)
    }
}
