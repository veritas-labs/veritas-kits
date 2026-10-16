plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
    id("app.cash.sqldelight")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.sqldelight.runtime)
            api(libs.sqldelight.asyncExtensions)
            api(libs.sqldelight.coroutinesExtensions)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.eygraber.sqldelightAndroidxDriver)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":store-kit-testing"))
        }
        // The kit's own tests reach DatabaseSafety, which apps must not.
        jvmTest { languageSettings.optIn("uk.veritaslabs.kits.KitsInternalApi") }
    }
}

sqldelight {
    // androidx.sqlite:sqlite-bundled already statically bundles its own SQLite; without this SQLDelight's default
    // `-lsqlite3` linker flag makes an iOS framework link fail looking for the system library.
    linkSqlite = false

    databases {
        create("KitsDatabase") {
            packageName.set("uk.veritaslabs.kits.store")
            // AndroidxSqliteDriver is a suspending driver.
            generateAsync = true
            // One <version>.db snapshot per schema version, checked in, so verifyMigrations can prove every .sqm
            // chain reaches the current schema (docs/database-migrations.md).
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}
