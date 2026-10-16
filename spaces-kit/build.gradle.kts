plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
    id("kits.serialization")
}

kotlin {
    // The services, the roster/registry/profile documents and the wire messages are public only so sync-kit and the app's
    // compatibility tests can reach them (@KitsInternalApi); the kit itself uses them freely.
    compilerOptions { optIn.add("uk.veritaslabs.kits.KitsInternalApi") }

    sourceSets {
        // The documents and services need Automerge (docs-kit) and x0x-kit, which have no iOS binding, so they are shared by
        // the two JVM-based targets, which have no common intermediate source set in the default hierarchy; both compile
        // this directory directly. iOS gets only commonMain (repositories, messages, link, listeners), which needs just
        // store-kit.
        androidMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")
        jvmMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")

        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(project(":store-kit"))
            implementation(libs.kotlinx.serializationJson)
        }
        val jvmAndroidDependencies: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit = {
            api(project(":x0x-kit"))
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
            // To open a database of its own and give it a trigger, which stands for a message projected over a row at the wrong moment.
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.eygraber.sqldelightAndroidxDriver)
        }
    }
}
