plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":store-kit"))
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.eygraber.sqldelightAndroidxDriver)
        }
    }
}
