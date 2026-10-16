package uk.veritaslabs.kits.store

/** The outcome of opening the local database at launch, for the app to show a message instead of crashing. */
public sealed interface DatabaseOpenResult {
    public data class Opened(public val database: KitsDatabase) : DatabaseOpenResult

    /** The file is from a newer version of the app; it was left untouched. See [DatabaseDowngradeException]. */
    public data class TooNew(public val error: DatabaseDowngradeException) : DatabaseOpenResult
}

/** Like [createKitsDatabase], but a database from a newer version comes back as [DatabaseOpenResult.TooNew]. */
public fun openKitsDatabase(driverFactory: DatabaseDriverFactory, appName: String): DatabaseOpenResult =
    try {
        DatabaseOpenResult.Opened(createKitsDatabase(driverFactory, appName))
    } catch (e: DatabaseDowngradeException) {
        DatabaseOpenResult.TooNew(e)
    }
