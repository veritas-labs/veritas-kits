plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}
