package uk.veritaslabs.kits.store

/** The outcome of opening the local database at launch, for the app to show a message instead of crashing. */
public sealed interface DatabaseOpenResult {
    public data class Opened(public val database: KitsDatabase) : DatabaseOpenResult

    /** The file is from a newer Vesta; it was left untouched. See [DatabaseDowngradeException]. */
    public data class TooNew(public val error: DatabaseDowngradeException) : DatabaseOpenResult
}

/** Like [createVestaDatabase], but a database from a newer version comes back as [DatabaseOpenResult.TooNew]. */
public fun openVestaDatabase(driverFactory: DatabaseDriverFactory): DatabaseOpenResult =
    try {
        DatabaseOpenResult.Opened(createVestaDatabase(driverFactory))
    } catch (e: DatabaseDowngradeException) {
        DatabaseOpenResult.TooNew(e)
    }
