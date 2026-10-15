// Applied to the root project only: the validator discovers the subprojects itself.
plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

apiValidation {
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}
