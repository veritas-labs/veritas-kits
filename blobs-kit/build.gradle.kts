plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
    id("kits.serialization")
}

kotlin {
    // BlobMessage is public only so apps' wire-compatibility tests can pin it; the kit itself uses it freely.
    compilerOptions { optIn.add("uk.veritaslabs.kits.KitsInternalApi") }

    sourceSets {
        // The store and the transfer service are shared by the two JVM-based targets, which have no common
        // intermediate source set in the default hierarchy, so both compile this directory directly. iOS
        // gets only commonMain (the photo types and Placeholder) and so needs neither x0x-kit nor store-kit.
        androidMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")
        jvmMain.get().kotlin.srcDir("src/jvmAndroidMain/kotlin")

        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
        }
        val jvmAndroidDependencies: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit = {
            api(project(":x0x-kit"))
            api(project(":store-kit"))
            implementation(libs.kotlinx.serializationJson)
        }
        androidMain.dependencies(jvmAndroidDependencies)
        jvmMain.dependencies {
            jvmAndroidDependencies()
            // The desktop app supplies Skia (Compose Multiplatform brings it), so the kit does not pin a second version.
            compileOnly(libs.skiko.awt)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":x0x-kit-testing"))
            implementation(project(":store-kit-testing"))
            implementation(libs.skiko.awt)
            implementation(libs.skiko.awtRuntimeLinuxX64)
        }
    }
}
