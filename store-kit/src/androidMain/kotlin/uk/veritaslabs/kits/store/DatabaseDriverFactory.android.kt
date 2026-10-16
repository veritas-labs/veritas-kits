package uk.veritaslabs.kits.store

import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import android.content.Context

public actual class DatabaseDriverFactory public actual constructor(private val location: DatabaseLocation) {
    private fun context() = requireNotNull(DatabaseAndroid.context) {
        "VestaAndroid.install(context) must be called before the database is opened"
    }

    public actual fun databasePath(): String = context().applicationContext.getDatabasePath(location.fileName).absolutePath

    public actual fun databaseType(): AndroidxSqliteDatabaseType = AndroidxSqliteDatabaseType.FileProvider { databasePath() }
}

/** The Android context the database path comes from; the app installs it (`VestaAndroid.install`) before the database is opened. */
public object DatabaseAndroid {
    @Volatile
    public var context: Context? = null
        private set

    public fun install(context: Context) {
        this.context = context.applicationContext
    }
}
