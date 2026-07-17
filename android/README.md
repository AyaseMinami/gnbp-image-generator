# GNBP Android

This directory contains the native Kotlin and Jetpack Compose edition of GNBP
Image Generator. It is currently an implementation scaffold, not a functional
image-generation release.

## Toolchain

- JDK 17
- Android SDK Platform 37
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1 through the checked-in Wrapper
- Jetpack Compose BOM 2026.06.00

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
