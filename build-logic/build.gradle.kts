plugins { `kotlin-dsl` }

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.android.gradlePlugin)
    implementation(libs.sqldelight.gradlePlugin)
    implementation(libs.binaryCompatibilityValidator.gradlePlugin)
}
