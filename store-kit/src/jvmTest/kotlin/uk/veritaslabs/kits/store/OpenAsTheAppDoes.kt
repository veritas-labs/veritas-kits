package uk.veritaslabs.kits.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import java.io.File

/** Opens [file] as `createVestaDatabase` does (same guard, backup and driver configuration) but at a chosen path. */
internal fun openAsTheAppDoes(file: File): Pair<KitsDatabase, AndroidxSqliteDriver> {
    DatabaseSafety.prepare(file.absolutePath, KitsDatabase.Schema.version)
    val driver = AndroidxSqliteDriver(
        driver = BundledSQLiteDriver(),
        databaseType = AndroidxSqliteDatabaseType.File(file.absolutePath),
        schema = KitsDatabase.Schema,
        configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
    )
    return KitsDatabase(driver) to driver
}
