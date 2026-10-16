package uk.veritaslabs.kits.store

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Stores under the app's sandboxed NSDocumentDirectory — private storage by
// construction, no extra configuration needed.
public actual class DatabaseDriverFactory public actual constructor(private val location: DatabaseLocation) {
    public actual fun databaseType(): AndroidxSqliteDatabaseType = AndroidxSqliteDatabaseType.File(databasePath())

    public actual fun databasePath(): String {
        val documentDirectory = NSSearchPathForDirectoriesInDomains(
            directory = NSDocumentDirectory,
            domainMask = NSUserDomainMask,
            expandTilde = true,
        ).first() as String
        return location.inDirectory(documentDirectory)
    }
}
