package uk.veritaslabs.kits.x0x.testing

import uk.veritaslabs.kits.x0x.*

import kotlinx.serialization.json.JsonObject

/**
 * In-memory [X0xClient] for tests: no daemon. Only the direct-message surface does anything —
 * everything else throws, so a test that reaches for it fails loudly.
 */
public class FakeX0xClient : X0xClient {

    /** One entry per [directEvents] call; the test drives the stream through it. */
    public class OpenStream(public val onMessage: (X0xDirectMessage) -> Unit, private val onClose: (Throwable?) -> Unit) : X0xEventStream {
        public var closed: Boolean = false
            private set

        public fun emit(message: X0xDirectMessage): Unit = onMessage(message)

        /** Simulates the server / connection ending the stream. */
        public fun drop(error: Throwable? = null): Unit = end(error)

        override fun close(): Unit = end(null)

        private fun end(error: Throwable?) {
            if (closed) return
            closed = true
            onClose(error)
        }
    }

    public val openedStreams: MutableList<OpenStream> = mutableListOf<OpenStream>()
    public val openAttempts: MutableList<Long> = mutableListOf<Long>()

    /** When non-null, the next [directEvents] calls throw this instead of opening a stream. */
    public var openFailure: Exception? = null

    public class Sent(public val agentId: String, public val payload: ByteArray)

    public val sent: MutableList<Sent> = mutableListOf<Sent>()

    /** When non-null, [sendDirect] throws this (after recording nothing). */
    public var sendFailure: Exception? = null

    override val endpoint: String = "fake"

    override suspend fun directEvents(onMessage: (X0xDirectMessage) -> Unit, onClose: (Throwable?) -> Unit): X0xEventStream {
        openFailure?.let { throw it }
        return OpenStream(onMessage, onClose).also { openedStreams += it }
    }

    override suspend fun sendDirect(agentId: String, payload: ByteArray): JsonObject {
        sendFailure?.let { throw it }
        sent += Sent(agentId, payload)
        return JsonObject(emptyMap())
    }

    override suspend fun presenceEvents(onEvent: (X0xPresenceEvent) -> Unit, onClose: (Throwable?) -> Unit): X0xEventStream = X0xEventStream { }

    override suspend fun sendFile(agentId: String, filename: String, size: Long, sha256: String, path: String): String = TODO()
    override suspend fun listFileTransfers(): List<X0xFileTransfer> = TODO()
    override suspend fun getFileTransfer(transferId: String): X0xFileTransfer? = TODO()
    override suspend fun acceptFile(transferId: String): Unit = TODO()
    override suspend fun rejectFile(transferId: String): Unit = TODO()
    override suspend fun fileEvents(onEvent: (X0xFileEvent) -> Unit, onClose: (Throwable?) -> Unit): X0xEventStream = X0xEventStream { }

    override suspend fun health(): X0xHealth? = null
    override suspend fun agent(): X0xAgent = TODO()
    override suspend fun createSession(): X0xSession = TODO()
    override suspend fun subscribe(topic: String): JsonObject = TODO()
    override suspend fun publish(topic: String, payload: ByteArray): JsonObject = TODO()
    override suspend fun gossipEvents(onMessage: (X0xGossipMessage) -> Unit, onClose: (Throwable?) -> Unit): X0xEventStream = TODO()
    override suspend fun connectAgent(agentId: String): JsonObject = TODO()
    override suspend fun listDirectConnections(): JsonObject = directConnections

    /** What [listDirectConnections] answers: `{ "connections": [ { "agent_id", "machine_id" } ] }`. */
    public var directConnections: JsonObject = JsonObject(emptyMap())

    public var peers: List<String> = emptyList()

    /** Per-peer answer of [getPeerHealth]; a peer left out answers null, one in [peerHealthFailures] throws. */
    public val peerHealth: MutableMap<String, X0xPeerHealth> = mutableMapOf<String, X0xPeerHealth>()
    public val peerHealthFailures: MutableSet<String> = mutableSetOf<String>()

    /** What [getDiscoveredAgent] answers per agent; one left out answers null. */
    public val discoveredAgents: MutableMap<String, X0xDiscoveredAgent> = mutableMapOf<String, X0xDiscoveredAgent>()

    override suspend fun getDiscoveredAgent(agentId: String): X0xDiscoveredAgent? = discoveredAgents[agentId]

    override suspend fun getTransportSnapshot(): X0xTransportSnapshot = TODO()

    override suspend fun probePeer(peerId: String, timeoutMs: Long): Long? = null

    override suspend fun listPeers(): List<String> = peers

    override suspend fun getPeerHealth(peerId: String): X0xPeerHealth? {
        if (peerId in peerHealthFailures) throw X0xApiException(500, "boom")
        return peerHealth[peerId]
    }
    override suspend fun createStore(name: String, topic: String, policy: String): String = TODO()
    override suspend fun listStores(): JsonObject = TODO()
    override suspend fun listStoreKeys(storeId: String): List<X0xStoreKey> = TODO()
    override suspend fun getValue(storeId: String, key: String): ByteArray? = TODO()
    override suspend fun putValue(storeId: String, key: String, value: ByteArray, contentType: String): Unit = TODO()
    override suspend fun deleteValue(storeId: String, key: String): Unit = TODO()
    override suspend fun joinStore(storeId: String, expectedOwner: String): Unit = TODO()
    override suspend fun openGroupStore(groupId: String, name: String): String = TODO()
    override suspend fun createGroup(name: String, description: String, preset: String?): String = TODO()
    override suspend fun listGroups(): List<X0xGroupSummary> = TODO()
    override suspend fun createGroupInvite(groupId: String, expirySecs: Long?, intendedJoiner: String?): String = TODO()
    override suspend fun joinGroup(invite: String): String = TODO()
    override suspend fun getGroupMembers(groupId: String): List<X0xGroupMember> = TODO()
    override suspend fun getGroupCreator(groupId: String): String? = TODO()
    override suspend fun setGroupMemberRole(groupId: String, agentId: String, role: String): Unit = TODO()
    override suspend fun removeGroupMember(groupId: String, agentId: String): Unit = TODO()
    override suspend fun leaveGroup(groupId: String): Unit = TODO()
    override suspend fun sendGroupMessage(groupId: String, body: String, kind: String): JsonObject = TODO()
    override suspend fun getGroupMessages(groupId: String): List<X0xGroupPublicMessage> = TODO()
}
