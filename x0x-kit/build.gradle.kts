import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("kits.kmp-library")
    id("kits.api-check")
    id("kits.publishing")
    id("kits.serialization")
}

kotlin {
    // The iOS half binds the embedded daemon through cinterop. The header is a copy of Vesta's
    // ffi/x0x-ffi/include/x0x_ffi.h (the Rust crate stays in Vesta until WP26). Cinterop cannot run on a
    // Linux host, so this part is unverified here.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("x0xffi") {
            defFile(project.file("native/x0xffi.def"))
            includeDirs(project.file("native/include"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
            api(libs.kotlinx.serializationJson)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutinesTest)
            implementation(project(":x0x-kit-testing"))
        }
    }
}
