package uk.veritaslabs.kits.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import uk.veritaslabs.kits.KitsInternalApi

/** Resolves what's platform-specific about opening [KitsDatabase]: where the file lives — see
 * the per-platform actuals. */
public expect class DatabaseDriverFactory public constructor(location: DatabaseLocation) {
    /** The database file, for the checks that run before the driver opens it. */
    public fun databasePath(): String

    public fun databaseType(): AndroidxSqliteDatabaseType
}

/** Opens the local [KitsDatabase] on the same bundled SQLite build across every platform,
 * creating its schema on first run.
 * [appName] names the app in the message of a [DatabaseDowngradeException].
 * @throws DatabaseDowngradeException if the file was written by a newer version; it is left untouched.
 * An older file gets a safety copy beside it before it is migrated (see [DatabaseSafety]). */
@OptIn(KitsInternalApi::class)
public fun createKitsDatabase(driverFactory: DatabaseDriverFactory, appName: String): KitsDatabase {
    DatabaseSafety.prepare(driverFactory.databasePath(), KitsDatabase.Schema.version, appName)
    val driver = AndroidxSqliteDriver(
        driver = BundledSQLiteDriver(),
        databaseType = driverFactory.databaseType(),
        schema = KitsDatabase.Schema,
        configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
    )
    return KitsDatabase(driver)
}
