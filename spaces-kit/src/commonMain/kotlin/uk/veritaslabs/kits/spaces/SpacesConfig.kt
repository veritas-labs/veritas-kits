package uk.veritaslabs.kits.spaces

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * What an app chooses about the way its Spaces are shared: the invite link scheme, the name of the x0x group a Space gets,
 * and how long the invites and hub commands it makes stay valid. [inviteScheme], [groupName] and [mailboxStoreName] are visible to other devices or stored on them, so an existing
 * install must keep them; the app says the first two (the kit has no brand of its own). The lifetimes are policy and may change freely.
 */
public data class SpacesConfig(
    /** Where an invite link starts (`<scheme>/<token>`). Links already shared use this one. */
    val inviteScheme: String,
    /** The name given to the x0x group created for a Space. Groups are never looked up by it. */
    val groupName: String,
    /** How long an invite link an admin shares stays usable. */
    val inviteLinkLifetime: Duration = 7.days,
    /** How long the x0x group invite sent to a member asking to join stays valid; the request is repeated every round if it lapses. */
    val joinInviteLifetime: Duration = 1.hours,
    /** How long the group invite in a hub's enrolment commands stays valid. */
    val hubEnrolmentLifetime: Duration = 1.hours,
    /** How often the hub screen makes fresh enrolment commands, so the ones shown are never close to expiry. */
    val hubCommandsRefresh: Duration = 30.minutes,
    /** The name of the Space group's mailbox store. It is stored in the x0x group, so it must not change for existing Spaces. */
    val mailboxStoreName: String = "mailbox",
)
