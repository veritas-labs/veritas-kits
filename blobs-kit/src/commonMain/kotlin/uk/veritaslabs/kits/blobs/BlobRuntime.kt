package uk.veritaslabs.kits.blobs

/** The running blob machinery of the app (ADR-023): what the screens use, and the background work that moves photos between devices. */
public interface BlobRuntime {
    public val blobs: Blobs

    /** Moves photos between devices for as long as it runs. Never returns normally. */
    public suspend fun run()
}
