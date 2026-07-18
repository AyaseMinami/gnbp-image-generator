# GNBP Android

This directory contains the native Kotlin and Jetpack Compose edition of GNBP
Image Generator. The offline-tested provider and transport contract slice and
secure persistence are implemented. The M4 media input/output slice passed code
review and its redefined API 26/29/33/36 device gate. M5 end-to-end generation
workflow work is in progress. The picker has a Compose entry point, but
provider, persistence, and media outputs are not connected to the generation
workflow yet; this is not a functional image-generation release.

## Toolchain

- JDK 17
- Android SDK Platform 37
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1 through the checked-in Wrapper
- Jetpack Compose BOM 2026.06.00
- OkHttp / MockWebServer 5.4.0
- Room 2.8.4 with KSP 2.3.10
- DataStore Preferences 1.2.1
- kotlinx.serialization 1.9.0
- kotlinx.coroutines 1.10.2
- Robolectric 4.16.1 for host-side Room tests

The application ID is `io.github.ayaseminami.gnbp` and `minSdk` is 26. Android
`versionName` and `versionCode` are defined only in `app/build.gradle.kts`.

Gradle 9.4.1 and Compose Compiler 2.2.10 are intentionally matched to AGP
9.2.0's documented Gradle default and built-in Kotlin version. Android lint may
report newer independent releases; upgrade the three together after checking
the AGP compatibility table.

## Verify

PowerShell:

```powershell
.\gradlew.bat lintDebug testDebugUnitTest :app:verifyDebugApkPageAlignment assembleDebugAndroidTest
```

Linux/macOS:

```bash
./gradlew lintDebug testDebugUnitTest :app:verifyDebugApkPageAlignment assembleDebugAndroidTest
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Build output is
ignored by Git.

Automated tests must remain offline and must never call a real image provider.
Do not commit `local.properties`, credentials, private endpoints, signing
stores, APK/AAB files, or generated images.

## Provider Transport

M2 provides typed Gemini and OpenAI-compatible adapters behind a shared
`ProviderHttpTransport` seam. It includes strict TLS, binding-scoped custom CA,
pinned-certificate, acknowledged trust-all and cleartext modes, normalized LAN
address policy, no automatic redirects or retries, and delivery-certainty
tracking for uncertain paid requests.

Production network-client construction is restricted to the provider transport
package by the `verifyNetworkChokepoint` Gradle task. `preBuild` depends on this
task, so it also runs in normal test, lint, APK, and CI builds.

## Secure Persistence

M3 provides Room-backed profile and prompt repositories, a DataStore-backed
settings repository, and a Keystore-backed AES-GCM key provider. API keys are
stored in Room only as versioned ciphertext plus IV; they are never placed in
DataStore preferences. Room migration `1 -> 2` adds the reviewed transport
binding fields with strict TLS defaults. Changing a saved profile's authority
requires first resetting it to strict TLS, so prior custom trust and pins cannot
be silently rebound to another host. Host-side DataStore tests use a
DataStore Core in-memory storage seam because Windows JVM file replacement does
not provide Android's atomic rename semantics; the production container uses
the normal Android preference file storage.

## Media Input And Output

M4 adds the Android Photo Picker contract, durable app-private copies of picked
content URIs, bounded reference-image decoding, and the desktop-compatible
1536-pixel/JPEG-85 preparation contract. Imports and generated images are capped
at 64 MiB, private URIs and paths are redacted from default string output, and
the source URI grant is not retained after the private copy is made.

Reference cleanup has an explicit policy: incomplete `.tmp` copies are eligible
after one hour; unreferenced durable copies are eligible after seven days; IDs
retained by a draft or task are never removed. The M4 picker draft is owned by a
ViewModel across activity recreation and deletes unsubmitted copies when the
owner is cleared. M5 owns startup cleanup after persistent task/draft
repositories can supply the retained-ID set.

Generated images use MediaStore. API 29 and newer use `RELATIVE_PATH` plus
`IS_PENDING`; API 26-28 require `WRITE_EXTERNAL_STORAGE` and use the legacy
`DATA` path. Failed writes remove the partial MediaStore row. Asset references
provide preview/share intents and can be copied back into the reference store.
Only an allowlist of non-secret generation parameters is exported through the
MediaStore description field.

## Generation Workflow (M5)

The first M5 slice connects the Generate and Tasks Compose screens to a
foreground `GenerationEngine`. It expands immutable batch snapshots, prepares
references off the main thread, enforces the saved concurrency limit, supports
queued and active cancellation, and requires explicit retry. A request whose
delivery may be uncertain becomes `OutcomeUnknown`; it is never retried
automatically. Task state is persisted in Room and startup changes interrupted
`Running` tasks to `OutcomeUnknown` without issuing a new request. The engine's
provider and asset-store seams have offline fakes, including a Compose
instrumentation workflow test.

The Generate screen currently consumes profiles persisted by M3. Profile
creation and advanced security editing remain part of M6 Settings; until a
profile exists, submission is intentionally disabled. The reliable background
execution and notification guarantees remain assigned to M7.

Host tests cover large images, revoked URI access, save rollback, external
deletion, scoped/legacy values, and collision-safe names. Blocking CI runs the
instrumentation suite on API 26, 29, 33, and 36 emulators. API 37.0 only offers
16 KB `google_apis_ps16k` images, and the current hosted-runner/image
combination remains offline even with KVM enabled; run
[`29646096937`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29646096937)
captures that failure before instrumentation starts. CI therefore keeps API 37
16 KB as a non-blocking compatibility signal instead of deleting it. Local
execution uses `connectedDebugAndroidTest` with a compatible device or
emulator. The instrumentation APK is also compiled in the normal verification
build.

The debug APK contains `libandroidx.graphics.path.so` and
`libdatastore_shared_counter.so` in four ABIs. The M4 baseline has all eight
entries aligned to 16 KB in the APK, and both arm64-v8a libraries use ELF
`LOAD` segment `p_align = 0x4000`. The blocking
`verifyDebugApkPageAlignment` Gradle task assembles the APK and runs
`zipalign -c -P 16` so a future dependency cannot silently regress package
alignment. This guard does not inspect ELF program headers; M7 must add a
blocking check for arm64 `LOAD` segments with `p_align < 0x4000`. Before any tag
release or application-store submission, the
repository maintainer acting as release owner must obtain a successful API 37
16 KB instrumentation run through Firebase Test Lab or a physical 16 KB device;
Codex records the evidence and Claude Code independently reviews it.

Android exposes the active network's NAT64 prefix only on API 30 and newer. On
API 26-29 the address classifier still recognizes the well-known
`64:ff9b::/96` prefix, but a carrier-specific prefix cannot be discovered and is
a documented platform limitation.
