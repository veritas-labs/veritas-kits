plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    jvm()
    android {
        namespace = "uk.veritaslabs.consumecheck"
        compileSdk = 37
        minSdk = 26
        withHostTest { }
    }
    iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation("uk.veritaslabs.kits:smoke-kit:0.1.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
