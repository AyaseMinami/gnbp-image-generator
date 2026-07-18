# GNBP Android

This directory contains the native Kotlin and Jetpack Compose edition of GNBP
Image Generator. The offline-tested provider and transport contract slice and
secure persistence are implemented, but they are not connected to the Compose
workflow yet and this is not a functional image-generation release.

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
.\gradlew.bat lintDebug testDebugUnitTest assembleDebug
```

Linux/macOS:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
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
