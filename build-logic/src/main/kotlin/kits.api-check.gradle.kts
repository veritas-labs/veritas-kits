plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    // Every declaration's visibility and return type must be written out.
    explicitApi()
}
