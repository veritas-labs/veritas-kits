package uk.veritaslabs.kits.x0x

/** Whether this device currently has a working link to the x0x network. */
public sealed interface X0xConnectivity {
    public data object Connecting : X0xConnectivity
    public data class Connected(val peers: Int) : X0xConnectivity
    public data object Offline : X0xConnectivity
}

/**
 * Whether this device is reaching the x0x network, as far as anyone can send it a message: not when the daemon is
 * offline or has no peers. Still connecting counts as reaching it, so nothing flashes a warning at startup.
 */
public val X0xConnectivity.isReachingNetwork: Boolean
    get() = when (this) {
        X0xConnectivity.Connecting -> true
        X0xConnectivity.Offline -> false
        is X0xConnectivity.Connected -> peers > 0
    }
