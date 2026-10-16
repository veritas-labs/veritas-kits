# Vesta Kits

Kotlin Multiplatform libraries extracted from Vesta. Apache-2.0 (see `LICENSE`, `NOTICE`).
Group `uk.veritaslabs.kits`, version in `gradle.properties` (`kitsVersion`). Local only: published to
Maven Local, no remote repository.

## Layout

- `build-logic/` convention plugins: `kits.kmp-library` (jvm, android via
  `com.android.kotlin.multiplatform.library`, `iosArm64`, `iosSimulatorArm64`, JVM 11),
  `kits.api-check` (explicit API mode), `kits.publishing` (`maven-publish`, group/version, Apache-2.0 POM),
  `kits.root-api-validation` (binary-compatibility-validator, root project only, klib dumps on).
- `x0x-kit/` the x0x client (REST/SSE/gossip/direct/stores/groups), package `uk.veritaslabs.kits.x0x`.
- `x0x-kit-testing/` `FakeX0xClient` and (JVM only) `FakeX0xNetwork`, package `uk.veritaslabs.kits.x0x.testing`.
- The throwaway `smoke-kit` and `consume-check` of WP12 are gone: Vesta itself is now the consumer check.

Versions copy Vesta's `gradle/libs.versions.toml` (Kotlin 2.4.20, AGP 9.1.1, Gradle 9.6.1).

## Consuming from Vesta

Vesta needs `mavenLocal()` filtered to the kits group in `dependencyResolutionManagement`:

    mavenLocal { content { includeGroup("uk.veritaslabs.kits") } }

and `api("uk.veritaslabs.kits:x0x-kit:0.1.0")` in `commonMain`, `uk.veritaslabs.kits:x0x-kit-testing` in
`commonTest`. Re-run `publishToMavenLocal` here after every change before building Vesta.

## Commands

    ./gradlew build apiCheck publishToMavenLocal     # Gate K
    ./gradlew apiDump                                # after an intentional API change; commit the dump

Consume-check = Vesta's Gate V (`./gradlew :shared:jvmTest :shared:testAndroidHostTest ...` after `publishToMavenLocal`).
Android host tests (`testAndroidHostTest`) run each kit's `commonTest` on the Android variant too.

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

## x0x-kit findings (WP13/WP14)

- **JNI exports still use the Vesta package.** The embedded daemon's Rust crate (`ffi/x0x-ffi` in Vesta) exports
  `Java_uk_veritaslabs_vesta_x0x_X0xBridge_*`, and the JVM binds them by the Kotlin class's fully qualified name. There
  is no NDK or emulator in the unattended environment, so the crate was not changed and `X0xBridge`
  (`x0x-kit/src/androidMain/.../uk/veritaslabs/vesta/x0x/X0xBridge.android.kt`) stays in the package
  `uk.veritaslabs.vesta.x0x` inside this kit. Everything else is in `uk.veritaslabs.kits.x0x`. Rename the package and
  the Rust exports together when native packaging is done by a human (Vesta plan WP26). Vesta's
  `X0xBridgeSymbolsTest` (androidHostTest) reads the compiled bridge class from this kit's AAR and compares its
  `native` methods with the exports in `lib.rs`, so drift is caught. Vesta's proguard keep rule for the bridge is unchanged.
- **Native libraries are not carried by the kit.** `libx0x_ffi.so` is still built by Vesta (`scripts/build-android-ffi.sh`,
  staged into `androidApp/src/main/jniLibs`) and the iOS `libx0x_ffi.a` by `scripts/build-ios-ffi.sh` and linked by Xcode.
  The kit only has the Kotlin declarations (`System.loadLibrary("x0x_ffi")` at first use).
- **iOS needs a Mac.** The iOS source set binds `x0x_ffi.h` through cinterop (`native/x0xffi.def`, header copied from Vesta's
  crate), and Kotlin/Native cannot run cinterop on Linux. So `x0x-kit` and `x0x-kit-testing` (listed in
  `kits.iosNeedsMacModules`, gradle.properties) declare iOS targets only on a macOS host: on Linux the published artifacts
  have jvm and android variants only. Consequences: Vesta's iOS source sets cannot resolve the kit until it is
  published from a Mac; the klib API dump is generated and checked on macOS only (`kits.root-api-validation`); the iOS code
  in this kit has never been compiled. Gate M owes: publish from a Mac, run `apiDump` there and commit the
  `*.klib.api` files, build Vesta's iOS framework.
- Public API is everything that was public in Vesta (with `public` written out); `httpRequest`, `openSseStream`,
  `X0xHttpResult` stay `internal`. No `@KitsInternalApi` yet (that annotation arrives with `store-kit`).
