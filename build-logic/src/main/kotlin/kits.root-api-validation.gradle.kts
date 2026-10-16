import org.jetbrains.kotlin.konan.target.HostManager

// Applied to the root project only: the validator discovers the subprojects itself.
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        // The klib (iOS) dump needs an iOS target in every module, and modules with a cinterop binding have none on
        // a Linux host (see kits.kmp-library). While any such module exists the klib dump and check are macOS-only:
        // run apiDump on a Mac to add `api/<module>.klib.api` files, and commit them.
        val macOnlyModules = providers.gradleProperty("kits.iosNeedsMacModules").orNull.orEmpty().isNotBlank()
        enabled = HostManager.hostIsMac || !macOnlyModules
    }
}
