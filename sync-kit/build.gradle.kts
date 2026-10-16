plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
    id("kits.serialization")
}

kotlin {
    // The engine reaches into spaces-kit's and docs-kit's plumbing (@KitsInternalApi) and offers a little of its own to the app's
    // compatibility tests; the kit itself uses all of it freely.
    compilerOptions { optIn.add("uk.veritaslabs.kits.KitsInternalApi") }

    sourceSets {
        // The engine and the Sync activity service need Automerge (docs-kit), which has no iOS binding, so they are shared by the
        // two JVM-based targets, which have no common intermediate source set in the default hierarchy; both compile this directory
        // directly. commonMain (pairing, the device list, the Sync activity models and verdict) needs x0x-kit and spaces-kit only.
        androidMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")
        jvmMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")

        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(project(":store-kit"))
            api(project(":x0x-kit"))
            api(project(":spaces-kit"))
            implementation(libs.kotlinx.serializationJson)
        }
        val jvmAndroidDependencies: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit = {
            api(project(":docs-kit"))
        }
        androidMain.dependencies(jvmAndroidDependencies)
        jvmMain.dependencies(jvmAndroidDependencies)
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":x0x-kit-testing"))
            implementation(project(":store-kit-testing"))
        }
    }
}
