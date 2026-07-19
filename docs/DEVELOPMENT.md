# Development Guide

## Desktop Architecture

`main.py` creates the Qt application and `MainWindow`. The UI snapshots each
request into `GenConfig` and sends it to `TaskManager`. Background workers route
the task to the Gemini or GPT-compatible client, save the returned image through
`ImageUtils`, and emit Qt signals back to the UI.

Key modules:

- `app_info.py`: application name, version, title, and executable name
- `config/config_mgr.py`: local profiles, prompt presets, settings, and task data
- `core/api_client.py`: Gemini-compatible requests
- `core/gpt_client.py`: OpenAI-compatible generation and edit requests
- `core/task_queue.py`: background worker pool and concurrency control
- `core/utils.py`: image encoding, collision-safe saving, and metadata
- `ui/main_window.py`: PySide6 user workflow
- `ui/themes.py`: QSS themes and runtime control assets

## Desktop Commands

```powershell
py -3.11 -m pip install -r requirements.txt
py -3.11 main.py
py -3.11 -m unittest discover -s tests -v
py -3.11 -m PyInstaller build.spec --clean --noconfirm
```

Tests must remain offline. Real API probes, local configuration, generated
images, and packaged executables are intentionally excluded from Git.

## Android Development

The native Kotlin/Compose application is a self-contained Gradle project in
`android/`. Its architecture and milestones are defined in
`ANDROID-DEVELOPMENT-PLAN.md`; the platform decision is recorded in
`adr/0001-native-android-implementation.md`.

M1-M7 have passed their implementation, independent-review, and blocking
API 26/29/33/36 device gates. The source tree is at the reliable-background
milestone, but no signed public APK exists yet. Remaining desktop-parity gaps are
tracked in `../contracts/PLATFORM-PARITY.md`; release-only evidence is tracked in
`ANDROID-SIDELOAD-RELEASE.md`.

The current Android project requires JDK 17 and Android SDK Platform 37. From
PowerShell:

```powershell
cd android
.\gradlew.bat check assembleDebugAndroidTest
```

The equivalent command on Linux/macOS and in CI is:

```bash
cd android
./gradlew check assembleDebugAndroidTest
```

`check` includes lint, the offline debug unit suite, the network-construction
chokepoint, APK ZIP alignment, and arm64 ELF `LOAD.p_align` verification. Device
instrumentation runs through `connectedDebugAndroidTest`; CI treats API 26, 29,
33, and 36 as blocking and retains API 37 16 KB as a non-blocking hosted-image
signal. A successful physical-device or Firebase Test Lab API 37 16 KB run is
still mandatory before a tag or public APK.

Android automated tests must remain offline. `local.properties`, signing
stores, APK/AAB output, credentials, private endpoints, and generated images
must not be committed.

The release-owner procedure for a signed side-loaded APK is documented in
`ANDROID-SIDELOAD-RELEASE.md`. Signing material remains outside the repository;
the checked-in Android build stays unsigned for release until the owner invokes
Android Studio's signing workflow.

## Versioning

Desktop versions come from `APP_VERSION` in `app_info.py`. The window title, Qt
application version, build output name, and batch build messages derive from it.

Android versions are independent and come from `android/app/build.gradle.kts`.
Use platform-prefixed release tags such as `desktop-v9.2.0` and
`android-v0.1.0`.

## Branches

- `main`: stable releases
- `dev`: active development and pull-request target

Keep `main` releasable and merge verified `dev` changes by fast-forward when
possible.
