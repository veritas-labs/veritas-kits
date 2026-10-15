# Vesta Kits

Kotlin Multiplatform libraries extracted from Vesta. Apache-2.0 (see `LICENSE`, `NOTICE`).
Group `uk.veritaslabs.kits`, version in `gradle.properties` (`kitsVersion`). Local only: published to
Maven Local, no remote repository.

## Layout

- `build-logic/` convention plugins: `kits.kmp-library` (jvm, android via
  `com.android.kotlin.multiplatform.library`, `iosArm64`, `iosSimulatorArm64`, JVM 11),
  `kits.api-check` (explicit API mode), `kits.publishing` (`maven-publish`, group/version, Apache-2.0 POM),
  `kits.root-api-validation` (binary-compatibility-validator, root project only, klib dumps on).
- `smoke-kit/` throwaway module proving the pipeline (deleted by the kits extraction plan WP14).
- `consume-check/` a separate throwaway build (not part of this build) that resolves `smoke-kit` from Maven Local.

Versions copy Vesta's `gradle/libs.versions.toml` (Kotlin 2.4.20, AGP 9.1.1, Gradle 9.6.1).

## Commands

    ./gradlew build apiCheck publishToMavenLocal     # Gate K
    ./gradlew apiDump                                # after an intentional API change; commit the dump
    cd consume-check && ./gradlew :shared:compileKotlinJvm :shared:compileAndroidMain :shared:jvmTest \
        :shared:testAndroidHostTest :desktop:compileKotlin :shared:compileKotlinIosSimulatorArm64

## Findings (WP12)

- AGP 9.1.1 `androidMultiplatformLibrary` publishes and is consumed fine. `publishToMavenLocal` produces
  `smoke-kit` (root, Gradle module metadata), `smoke-kit-jvm` (jar), `smoke-kit-android` (aar),
  `smoke-kit-iosarm64` and `smoke-kit-iossimulatorarm64` (klib). A consumer depending on
  `uk.veritaslabs.kits:smoke-kit:0.1.0` resolves `-jvm` for jvm runtime and `-android` for android runtime.
- Gotcha: binary-compatibility-validator must NOT be applied through a `plugins { id(..) version .. }` block in the
  root build file, nor via a version-catalog plugin alias. That puts the Kotlin Gradle plugin in a second
  classloader and every convention plugin fails with `ClassNotFoundException: KotlinMultiplatformExtension`.
  It is on the `build-logic` classpath and applied by the `kits.root-api-validation` precompiled plugin.
- Binary-compatibility-validator 0.18.1 produces a JVM dump (`api/jvm/`) and a klib dump (`api/*.klib.api`,
  iOS targets). The Android target of the AGP 9 library plugin has no dump of its own; the JVM dump is
  the guard for the shared source.
- iOS: `compileKotlin*` and klib tasks run on Linux; `linkDebugTest*`/`iosSimulatorArm64Test` are SKIPPED/disabled
  (need macOS). iOS runtime behaviour is unverified here.
- Not verified: an actual Android app module (`com.android.application`) consuming the aar, and Vesta itself.
  Only a Vesta-like KMP library (jvm + android + ios) and a plain Kotlin/JVM module were used.
