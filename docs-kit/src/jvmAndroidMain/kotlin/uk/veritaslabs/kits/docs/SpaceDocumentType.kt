package uk.veritaslabs.kits.docs

import kotlinx.coroutines.flow.Flow

/** Who a [SpaceDocumentType] is shared with. */
public enum class Scope {
    /** Synced with the user's paired devices and the Space's members and hubs, carried by the mailbox, acknowledged. */
    Shared,

    /** Synced with the user's own paired devices only: no mailbox, no other members. */
    Private,
}

/**
 * One kind of per-Space document (Pantry, Tasks, ...), described once so the engine never has to name it.
 *
 * Its document id is `"$spaceId:$feature"` (ADR-006). The feature strings are on the wire and in stored rows, so they
 * must never change for an existing type.
 *
 * @property feature the id suffix, for example `pantry`; must not contain `:` (the registry parses on the last one).
 * @property module the Sync activity module name events for this type are filed under.
 * @property scope see [Scope].
 * @property open the document for a Space, or null when there is no such Space or this device does not have it yet.
 * @property localChanges the Space id each time this type's document is edited on this device.
 * @property afterReceive runs right after a sync message for this type has been applied, also for one replayed from the buffer of
 * messages that arrived before their Space (the roster projects its members).
 * @property afterSync runs once such a message, received live, has been applied and recorded, before the sender is answered; it is
 * not run for a replayed one (the roster publishes this device's own member and devices).
 * @property resyncsHome true when an edit to this type changes who the Space's documents go to (the roster), so the engine also
 * syncs every other shared document of that Space after it.
 */
public class SpaceDocumentType(
    public val feature: String,
    public val module: String,
    public val scope: Scope,
    public val open: suspend (spaceId: String) -> SpaceDocument?,
    public val localChanges: Flow<String>,
    public val afterReceive: suspend (spaceId: String) -> Unit = {},
    public val afterSync: suspend (spaceId: String) -> Unit = {},
    public val resyncsHome: Boolean = false,
) {
    /** This type's document id in [spaceId]. */
    public fun documentId(spaceId: String): String = "$spaceId:$feature"
}
