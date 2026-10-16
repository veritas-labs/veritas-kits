# Vesta Kits

Kotlin Multiplatform libraries extracted from Vesta. Apache-2.0 (see `LICENSE`, `NOTICE`).
Group `uk.veritaslabs.kits`, version in `gradle.properties` (`kitsVersion`). Local only: published to
Maven Local, no remote repository.

## The kits and how they depend on each other

| Kit | What | Needs (api) | iOS klibs from Linux? |
|---|---|---|---|
| `x0x-kit` | x0x client (REST/SSE, gossip, direct, stores, groups), embedded-daemon bridge | none | no (cinterop: Mac host only) |
| `x0x-kit-testing` | `FakeX0xClient`, `FakeX0xNetwork` | x0x-kit | no (as x0x-kit) |
| `store-kit` | the SQLite schema (`KitsDatabase`), open path, downgrade guard, `@KitsInternalApi` | none | yes |
| `store-kit-testing` | `openInMemoryDatabase()` | store-kit | yes |
| `blobs-kit` | photo store, ThumbHash placeholder, photo encoders, `blob.want` transfer | x0x-kit, store-kit | yes (common photo types only) |
| `docs-kit` | per-Space Automerge documents, mailbox, sync envelope, activity recorder | x0x-kit, store-kit | none (Automerge has no iOS binding) |
| `spaces-kit` | Spaces, members, rosters, invites, registries, profiles, join/group/hub services | store-kit, x0x-kit, docs-kit | commonMain part only |
| `sync-kit` | device data sync engine, device pairing, sync activity models | store-kit, x0x-kit, spaces-kit, docs-kit | no (as x0x-kit) |

Publish order is the order Gradle already derives from the `project(...)` dependencies:
x0x-kit, store-kit, then x0x-kit-testing, store-kit-testing, blobs-kit, docs-kit, then spaces-kit, then sync-kit.
`./gradlew publishToMavenLocal` publishes all eight in one build; there is no need to order by hand. Every kit has the
same version (`kitsVersion`); bump it for all kits on every release.

## Build, publish, consume

Prerequisites: JDK 17+ (toolchains resolve JVM 11 targets through foojay), the Android SDK (`ANDROID_HOME` or a
gitignored `local.properties` containing `sdk.dir=/path/to/Sdk`), network for dependencies on a first run. Nothing else
outside git is needed: `./gradlew build apiCheck publishToMavenLocal` was run in a fresh `git clone` of this repo on
2026-10-16 and passed (the Gradle build cache was on, so unchanged task outputs may have come from cache; add
`--no-build-cache` for a from-scratch run).

    ./gradlew build apiCheck publishToMavenLocal     # Gate K: test, API check, publish to ~/.m2
    ./gradlew apiDump                                # after an intentional API change; commit the dump
    scripts/check-version-drift.sh [path/to/Vesta]   # kits vs Vesta libs.versions.toml; exit 1 on drift

Consuming apps add `mavenLocal { content { includeGroup("uk.veritaslabs.kits") } }` (filtered, so a stale copy of
anything else cannot resolve from it) and depend on `uk.veritaslabs.kits:<kit>:<kitsVersion>`. Re-run
`publishToMavenLocal` here after every change, then build the app (Vesta's Gate V). Local only: there is no remote
repository and nothing is pushed.

Version drift: the kits' `gradle/libs.versions.toml` starts as a copy of Vesta's. Kotlin, AGP, coroutines, serialization,
androidx-sqlite, the eygraber driver and Automerge must agree; run the drift script before every release. SQLDelight,
skiko and the binary-compatibility validator are kits-only. Vesta no longer declares SQLDelight at all.

## Things to know

- **JNI exception.** `X0xBridge` stays in package `uk.veritaslabs.vesta.x0x` (inside `x0x-kit`), because the Rust crate
  in Vesta exports `Java_uk_veritaslabs_vesta_x0x_X0xBridge_*`. Rename the package and the five exports together when a
  human does native packaging (Vesta plan WP26). The `.so` and the iOS static library are still built by Vesta.
- **iOS klibs are Mac-only to publish in full.** `x0x-kit`, `x0x-kit-testing` and `sync-kit` declare iOS targets only on
  a macOS host. Published from Linux they have jvm and android variants only, so Vesta's iOS build cannot resolve them
  until the same version is also published from a Mac (`publishToMavenLocal` on both machines, same `kitsVersion`), or
  the iOS app is held on pre-extraction `:shared` source. No Mac has been available; the choice and the open gates are
  in Vesta's `docs/plans/kits-extraction-followups.md`.
- **Public surface.** `explicitApi()` everywhere. Plumbing that another kit or the app's tests need is `public` under
  `@KitsInternalApi` (`@RequiresOptIn`, ERROR level, in `store-kit`). Everything else is `internal`.
- **The kits have no brand and no feature ids.** The app passes every choice: `SpacesConfig(inviteScheme, groupName)`, `DeviceSyncConfig(pairingScheme, groupName)`, link schemes to `InviteLink`/`PairingLink`, `DatabaseLocation`, `createKitsDatabase(driverFactory, appName)` / `openKitsDatabase`, `DeviceBlobRuntime(userAgent = ...)`, `DeviceDataSyncService(sendOrder = ...)`. None has a default naming the first app. `NoAppKnowledgeTest` (store-kit) fails if any kit main source has a feature id or `Vesta` in a string literal (the JNI and generated-package names are the documented exceptions).
- **Persisted and wire names are frozen** (tables, columns, document ids, message kinds and JSON fields, link layouts):
  see the per-kit notes below and Vesta's ADR-037. Do not "tidy" them.

## Owed gates and release checklist

Owed (nobody has run them; they need a human, a device or a Mac):

- **Gate D** (Android emulator with the real x0x daemon): the embedded daemon starts, pairing and join work between two devices.
- **Gate M** (a Mac): publish from the Mac, `apiDump` there and commit the `*.klib.api` files, build Vesta's iOS framework.
- **Real `vesta.db` copy check**: open a copy of a real user database (not the fixture) with the new build before deleting any backup.
- **Two-version check**: an old-build device and a new-build device in one Home each receive the other's edits.

Release checklist (a new `kitsVersion`):

1. `scripts/check-version-drift.sh` is clean.
2. On a Mac as well: `./gradlew apiDump`, commit the `api/*.klib.api` changes.
3. `./gradlew build apiCheck publishToMavenLocal` (Linux, fresh clone is best).
4. Bump `kitsVersion` here and `kits` in Vesta's `gradle/libs.versions.toml` together.
5. In Vesta: `./gradlew clean` then Gate V (`:shared:jvmTest :shared:testAndroidHostTest :desktopApp:compileKotlin :androidApp:compileDebugKotlin --continue`) and `generateLicenses` (the kits themselves are not listed as third parties).
6. Gate D on an emulator; copy a real `vesta.db` and open it; then tag.

## Layout

- `build-logic/` convention plugins: `kits.kmp-library` (jvm, android via
  `com.android.kotlin.multiplatform.library`, `iosArm64`, `iosSimulatorArm64`, JVM 11),
  `kits.api-check` (explicit API mode), `kits.publishing` (`maven-publish`, group/version, Apache-2.0 POM),
  `kits.root-api-validation` (binary-compatibility-validator, root project only, klib dumps on).
- `x0x-kit/` the x0x client (REST/SSE/gossip/direct/stores/groups), package `uk.veritaslabs.kits.x0x`.
- `x0x-kit-testing/` `FakeX0xClient` and (JVM only) `FakeX0xNetwork`, package `uk.veritaslabs.kits.x0x.testing`.
- `store-kit/` the SQLite schema (`KitsDatabase`, 21 `.sq`, 19 `.sqm`, 20 snapshots), the open path and the downgrade guard, package `uk.veritaslabs.kits.store`; also `KitsInternalApi`.
- `store-kit-testing/` `openInMemoryDatabase()`.
- `blobs-kit/` content-addressed photo store, `Placeholder` (ThumbHash) and photo encoders, the `blob.want` transfer service over x0x; package `uk.veritaslabs.kits.blobs`. Apps supply a `BlobReferenceSource` (which blobs their documents refer to). The wire field of `blob.want` is still `homeId` (`@SerialName`). Its iOS klibs have only the common photo types (no cinterop, so they publish from Linux); the Skia encoder is `compileOnly` on skiko-awt (the app brings Skia).
- `docs-kit/` the per-Space Automerge document plumbing: `SpaceDocumentType`/`Registry`/`Store`, `AutomergeDocumentCore`, the mailbox, `SyncEnvelope`, the sync activity recorder; package `uk.veritaslabs.kits.docs`. JVM and Android only (Automerge has no iOS binding: `kits.noIosModules` in `gradle.properties`); the app adds `org.automerge:androidnative`. The mailbox, heads helpers, `ChangesWaiting` and `SyncAcknowledgement` are plumbing for spaces-kit and sync-kit, public under `@KitsInternalApi`. Document ids (`<spaceId>:<feature>`) and the sync activity strings are persisted and on the wire.
- `spaces-kit/` membership: Spaces (`SpaceRepository`, kind is a string the app chooses), members and roles, the per-Space roster, the user's Space registry and Profile documents, invite links, the join / x0x-group / hub services and their wire messages, `SpacesConfig` (invite scheme, group and store names, lifetimes) and the `SpaceSeeding` hook; package `uk.veritaslabs.kits.spaces`. `commonMain` (repositories, messages, `InviteLink`, listeners, `ProfileRepository`) has iOS klibs and needs only store-kit; the Automerge/x0x services and documents (`SpaceRosters`, `SpaceRegistry`, `SpaceJoinService`, `SpaceGroups`, `SpaceHubs`, `ProfileRegistry`) are JVM and Android only and are `@KitsInternalApi`, as are the wire messages and roster/registry document functions (plumbing for sync-kit and for the app's compatibility tests). Persisted and wire names are unchanged: the SQL tables, the JSON fields of the `homejoin.*` / `homegroup.*` messages (`@SerialName("homeId")`, `"homeName"`, `"shape"`), the Automerge key `shape`, the document id `user:homes`, the invite link layout `<scheme>/<base64url(spaceId:secret:expiresAt:agents:spaceName)>`.
- `sync-kit/` the device data sync engine and device pairing: `DeviceDataSyncService` (syncs a user's Space registry, Profile and each Space's documents between their paired devices and Space members over x0x direct messages and the mailbox; the app supplies its `SpaceDocumentType`s, a `SpaceSeeding` and the `SpacesConfig`), `DeviceDataSync` (what the app holds it by), `DeviceSyncService` and `PairingLink` (pairing a second device), `SyncedDeviceRepository`, `DeviceSyncConfig`, `SyncActivityService` (internal) and the Sync activity models and `SyncVerdict`; package `uk.veritaslabs.kits.sync`. `commonMain` (pairing, devices, the activity models and verdict) needs x0x-kit and spaces-kit, so like x0x-kit it declares iOS targets only on a macOS host (`kits.iosNeedsMacModules`); the engine itself is JVM and Android only (docs-kit). The engine's send order is the app's: `DeviceDataSyncService(sendOrder = listOf(<feature ids>))` (the roster is always last, features not listed go after the listed ones in registry order, empty means registry order); Vesta's list is persisted behaviour (ADR-035) and unchanged, and the mailbox order is the registry's; the group message field `homeId` is still read by name. Its tests run the engine over `FakeX0xNetwork` with synthetic note documents registered under nine feature ids and modules (the test's own `TestSendOrder`; so the send order, the mailbox and the activity log are checked without an app's documents); the daemon integration tests are opt-in (`VESTA_X0X_DEVICE_DIRS`) and skipped without a daemon. Test hooks the app's own integration tests use (`admit`, `removeMember`, `rosterDevices`, `syncEverything`, `syncWith`, `rosterDocumentType`) are `@KitsInternalApi`.
- The throwaway `smoke-kit` and `consume-check` of WP12 are gone: Vesta itself is now the consumer check.

Versions copy Vesta's `gradle/libs.versions.toml` (Kotlin 2.4.20, AGP 9.1.1, Gradle 9.6.1).

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

## store-kit findings (WP15)

- SQLDelight 2.4.0 (`generateAsync = true`, `linkSqlite = false`, eygraber androidx driver 0.2.2) generates `KitsDatabase` in
  a kit module under `explicitApi()` with no opt-out: the generated code is not checked. The plugin is on the `build-logic`
  classpath (same classloader rule as binary-compatibility-validator) and applied as `id("app.cash.sqldelight")`.
- `.sq`, `.sqm` and snapshot files are byte-identical copies (SHA-256 compared). The `.sq` files still sit in
  `uk/veritaslabs/vesta/db/`, so the generated query classes (`HomeQueries`, rows `Profile`, `SyncEvent`) keep the package
  `uk.veritaslabs.vesta.db`; only the database class is `uk.veritaslabs.kits.store.KitsDatabase`. Moving them is a later
  schema-neutral rename.
- `./gradlew :store-kit:verifyCommonMainKitsDatabaseMigration` is the migration check (part of `build`/`check`);
  `:store-kit:generateCommonMainKitsDatabaseSchema` writes a new snapshot. See `docs/database-migrations.md`.
- `DatabaseSafety` is `public` under `@KitsInternalApi` (`@RequiresOptIn` ERROR) because Vesta's compatibility tests open
  files at a chosen path. `KitsInternalApi` lives in `store-kit`.
- Tests left in Vesta because they use Vesta types: `AssistantMigrationTest` (AssistantRepository), `AgentCommandLogTest`
  (DatabaseCommandLog), `DesktopDatabasePathTest` (Vesta's location). The old-database fixture `vesta-v20.db` is also
  copied here (`OldDatabaseStillOpensTest`, `SchemaGuardTest`).
- iOS: `compileKotlinIosArm64` and `IosSimulatorArm64` compile on Linux (no cinterop here), so store-kit publishes iOS klibs;
  nothing of it has run. The klib API dump is Mac-only (see x0x-kit findings); Gate M owes the dump.
