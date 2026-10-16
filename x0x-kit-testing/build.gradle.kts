plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":x0x-kit"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}
