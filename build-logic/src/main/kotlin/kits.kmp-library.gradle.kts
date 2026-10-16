import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    android {
        // uk.veritaslabs.kits.smoke_kit -> dashes are not legal in a package name.
        namespace = "uk.veritaslabs.kits." + project.name.removeSuffix("-kit").replace('-', '_')
        compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        // Runs commonTest on the Android variant too, on the host JVM (no device).
        withHostTest { }
    }
    // iOS targets are declared everywhere, and their tasks are skipped on Linux hosts, except for modules
    // listed in `kits.iosNeedsMacModules` (gradle.properties): those use cinterop, which Kotlin/Native cannot
    // run on Linux, so their klibs (and their dependants') cannot be built there and the publication would
    // fail. On Linux they get no iOS target at all, so their Maven Local artifacts carry no iOS variant.
    val iosNeedsMac = providers.gradleProperty("kits.iosNeedsMacModules").orNull.orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (project.name !in iosNeedsMac || HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}
