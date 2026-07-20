# GNBP Android

This directory contains the native Kotlin and Jetpack Compose edition of GNBP
Image Generator. M1-M7 are implemented and have passed independent review and
their blocking API 26/29/33/36 device gates. This includes provider transport,
secure persistence, media input/output, end-to-end generation, the complete MVP
UI, and reliable background execution. No public APK is released until the API
37 16 KB device evidence and signed-release acceptance gate also pass.

The Android edition implements the core desktop generation workflow but is not
a screen-for-screen port. Current platform replacements and remaining parity
work are recorded in
[`../contracts/PLATFORM-PARITY.md`](../contracts/PLATFORM-PARITY.md).

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
.\gradlew.bat check assembleDebugAndroidTest
```

Linux/macOS:

```bash
./gradlew check assembleDebugAndroidTest
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Build output is
ignored by Git. `check` includes lint, the offline debug unit suite, the
network-construction chokepoint, `zipalign -P 16`, and the arm64 ELF
`LOAD.p_align` guard.

Automated tests must remain offline and must never call a real image provider.
Do not commit `local.properties`, credentials, private endpoints, signing
stores, APK/AAB files, or generated images.

### Manual Provider Smoke Test

The `providerSmokeTest` Gradle task performs one real image-generation request
using the repository-root `test_api.txt`. It is deliberately excluded from
`testDebugUnitTest`, `check`, and CI. Run it only after the request and any
provider charge have been explicitly authorized:

```powershell
.\gradlew.bat providerSmokeTest
```

The task does not print the API key, endpoint, model, prompt, provider response
message, or returned image bytes. It reports only success or a sanitized error
category. `test_api.txt` must remain Git-ignored.

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

Room migration `3 -> 4` preserves task history while normalizing any legacy
duplicate retry lineage, then adds a unique `source_task_id` index. Explicit
retry uses an atomic insert-or-return operation so concurrent commands converge
on one persisted direct replacement and one provider request.

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
owner is cleared. Since M5, startup cleanup uses the persistent task/draft
repositories' retained-ID set.

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
`Running` tasks to `OutcomeUnknown` without issuing a new request. Safe
`Queued` tasks rebuild their execution snapshot from the persisted request,
current encrypted profile, and app-owned reference copies before resuming.
Explicit retry uses the same rebuild path after process restart; it never
retries `OutcomeUnknown` automatically. The public engine contract contains
product-level IDs and asset references rather than Android `Uri`, `File`, or
profile-persistence objects. The engine's provider and asset-store seams have
offline fakes, including a Compose instrumentation workflow test.

The M5 workflow passed independent review, a manually authorized
OpenAI-compatible provider smoke, and the user gate. Submission remains
disabled until a profile exists and while the prompt is blank. The reliable
background execution guarantees originally deferred by M5 were delivered and
independently reviewed in M7.

## MVP Completion (M6)

The M6 branch adds four primary views: Generate, Tasks, Gallery, and Settings.
Settings owns API-profile CRUD, encrypted-key replacement, prompt presets,
system-verified TLS, custom CA, pinned-certificate, explicitly acknowledged
trust-all/cleartext modes, LAN opt-in, preview behavior, concurrency, and
completion-notification choices. Unsafe profiles remain visibly marked on both
Settings and Generate.

Gallery derives results from persisted successful tasks and provides bounded
thumbnails, preview, Android sharing, and reuse through the durable reference
copy path. Task diagnostics map typed failures to localized guidance without
showing provider text, prompts, endpoints, or secrets. Completion notifications
introduced in M6 are now owned by the M7 foreground runtime, which also supplies
ongoing work notification and process-death reconciliation.

The signed side-loaded APK procedure and acceptance checklist are documented in
[`../docs/ANDROID-SIDELOAD-RELEASE.md`](../docs/ANDROID-SIDELOAD-RELEASE.md).
M6 passed 100 offline Android tests, independent security-focused review, and
the blocking API 26/29/33/36 device matrix in CI run
[`29681595718`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29681595718).
The user formally released the milestone on 2026-07-19.

## Reliable Background Execution (M7)

M7 moves the only production `GenerationEngine` out of the Activity/ViewModel
lifecycle and into an application-scoped runtime controlled by a non-exported
`dataSync` foreground service. Enqueue, cancellation, and explicit retry acquire
a command lease and start foreground work before accessing that singleton. The
ongoing notification contains only static status text and queued/running counts;
it never includes prompts, profile names, endpoints, content URIs, or keys.
Completion-notification preferences do not disable the mandatory foreground
service notification. On Android 13 and newer, if the user denies notification
permission, Android may omit that notification from the drawer while still
showing the foreground service in the system's active-apps/task-manager surface.

The service requests sticky restart and the Activity also resumes persisted
active work when the user reopens the app. A recreated engine safely resumes
`Queued` tasks. Any request that was `Running` when its process or service was
interrupted becomes `OutcomeUnknown` and is never retried automatically. An
orderly foreground-service timeout first cancels and joins active transport work
before writing that terminal state. If platform shutdown time expires first,
the service exits while the application-scoped cancellation/join operation keeps
its single ownership; it never writes a final interruption state ahead of the
worker. These mechanisms reduce Android lifecycle loss; they do not override
force-stop, device shutdown, platform foreground-work limits, or OEM process
policy.

After a provider returns an image, the engine atomically stages the bytes and
sanitized generation metadata in app-private storage. It then publishes to
MediaStore, journals the returned content URI, commits the Room success state,
and removes the private files. On process restart, a complete receipt restores
the task association; a staged response finishes the local MediaStore save
without calling the provider again. The journal never contains API keys or
endpoints and is excluded from Android backup and device transfer.

MediaStore and the private receipt journal cannot share one transaction. If the
process dies after MediaStore publishes an image but before its receipt becomes
durable, recovery can create one duplicate local gallery image. This
at-least-once edge never repeats the provider request or charge and does not
lose the generated bytes.

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
alignment. It now depends on `verifyDebugArm64ElfPageAlignment`, which parses
every packaged arm64 ELF program header and rejects `LOAD` segments with
`p_align < 0x4000`. These checks currently exercise two packaged AndroidX arm64
libraries rather than passing an empty library set. Before any tag release or
application-store submission, the
repository maintainer acting as release owner must obtain a successful API 37
16 KB instrumentation run through Firebase Test Lab or a physical 16 KB device;
Codex records the evidence and Claude Code independently reviews it.

M7 passed 114 offline Android tests, independent review of nine reliability and
security invariants, and the blocking API 26/29/33/36 device matrix in CI run
[`29690276894`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29690276894).
The API 37 16 KB job reproduced its approved non-blocking hosted-image boot
timeout. The user formally released the milestone on 2026-07-19; this milestone
decision does not waive the API 37 evidence required before a tag or public APK.

Android exposes the active network's NAT64 prefix only on API 30 and newer. On
API 26-29 the address classifier still recognizes the well-known
`64:ff9b::/96` prefix, but a carrier-specific prefix cannot be discovered and is
a documented platform limitation.
