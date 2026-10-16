plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
}

kotlin {
    // The mailbox and sync plumbing is public only so spaces-kit, sync-kit and the app's compatibility tests can reach it
    // (@KitsInternalApi); the kit itself uses it freely.
    compilerOptions { optIn.add("uk.veritaslabs.kits.KitsInternalApi") }

    sourceSets {
        // Automerge has no iOS binding, so this kit has no iOS target (kits.noIosModules). The code is shared by the two
        // JVM-based targets, which have no common intermediate source set in the default hierarchy, so both compile
        // this directory directly.
        androidMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")
        jvmMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")

        val jvmAndroidDependencies: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit = {
            api(project(":x0x-kit"))
            api(project(":store-kit"))
            api(libs.kotlinx.coroutinesCore)
            // Document, SyncState and ChangeHash appear in the public API. The Android natives (org.automerge:androidnative)
            // are the app's to add.
            api(libs.automerge)
        }
        androidMain.dependencies(jvmAndroidDependencies)
        jvmMain.dependencies(jvmAndroidDependencies)
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":store-kit-testing"))
        }
    }
}
