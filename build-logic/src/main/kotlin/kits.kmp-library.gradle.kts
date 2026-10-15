import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    }
    // iOS targets are declared everywhere; their tasks are skipped on Linux hosts.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }
}
