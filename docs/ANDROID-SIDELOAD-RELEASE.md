# Android Sideload Release Guide

Status: M6 passed; M7 reliability implementation and public release evidence remain pending

This guide covers the signed APK workflow for the first side-loaded Android
edition. It does not authorize a release by itself. The M6 test/review gate has
passed, but a tag or public APK still requires the M7 reliability gate and the
API 37 16 KB device evidence recorded in the development plan.

## 1. Keep Signing Material Outside The Repository

- Create or select the release keystore through Android Studio's **Build >
  Generate Signed Bundle / APK > APK** workflow.
- Store the keystore outside this checkout and outside cloud-synchronized
  folders unless it is protected by an approved secret-management process.
- Never add keystore paths, aliases, passwords, exported certificates, or
  signing Gradle properties to the repository, CI variables, issue text, or
  build logs.
- Back up the keystore and its recovery information separately. Losing it means
  future APKs cannot be signed as the same application identity.

The repository ignores `*.jks`, `*.keystore`, APK/AAB output, `local.properties`,
and Gradle build directories. This is a guardrail, not a substitute for checking
the staged diff before every release commit.

## 2. Prepare The Candidate

1. Set the monotonic `versionCode` and public `versionName` in
   `android/app/build.gradle.kts`.
2. Run the offline verification suite from `android/`:

   ```powershell
   .\gradlew.bat lintDebug testDebugUnitTest :app:verifyDebugApkPageAlignment assembleDebugAndroidTest
   ```

   `verifyDebugApkPageAlignment` also runs the blocking arm64 ELF program-header
   check; both ZIP alignment and every arm64 `LOAD.p_align` must satisfy 16 KB.
3. Confirm the blocking device matrix is green on API 26, 29, 33, and 36.
   API 37 16 KB remains a non-blocking hosted-CI signal, but a successful
   Firebase Test Lab or physical 16 KB device run is mandatory before any tag or
   public distribution.
4. Run a real-provider smoke only with explicit authorization. Never include
   `test_api.txt`, provider reports, private endpoints, prompts, or returned
   images in the release artifact or commit.

## 3. Build And Verify The Signed APK

Use Android Studio's signed-APK wizard with the `release` build variant. The
output remains under an ignored `app/build/` directory. Then verify it with the
SDK tools matching the installed build-tools version:

```powershell
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs <signed-apk>
& "$env:ANDROID_HOME\build-tools\36.0.0\zipalign.exe" -c -P 16 -v 4 <signed-apk>
```

Check that `apksigner` reports the expected release certificate rather than the
Android debug certificate. Record only the public certificate digest and tool
results in release evidence; do not record keystore locations or credentials.

## 4. Sideload Acceptance Check

Install the candidate on a clean test device:

```powershell
adb install <signed-apk>
```

Verify both the default Chinese resources and the English resources by changing
the system locale. In light and dark system themes, exercise:

- create, edit, select, and delete an API profile;
- strict TLS and every relay compatibility mode intended for that release;
- create and apply a prompt preset;
- text-to-image and reference-image generation;
- batch queue, cancellation, explicit retry, and `OutcomeUnknown` warning;
- Gallery preview, Android share, and reuse as a reference image;
- completion notification permission, audible/silent settings, and task
  diagnostics;
- background a multi-task queue, lock the screen, and confirm the ongoing
  foreground notification remains free of prompts, endpoints, URIs, and keys;
- interrupt the process during one running and one queued task, then relaunch:
  the running task must become `OutcomeUnknown`, the queued task may resume, and
  neither request may be retried automatically;
- interrupt after a fake/offline provider response is staged or after its
  MediaStore receipt is journaled; relaunch must recover the result association
  without making another provider request;
- cancel an active request from Tasks and confirm the transport call stops and
  the foreground service exits after all work becomes terminal;
- activity rotation while editing and while viewing every main screen;
- API 26 legacy save permission and API 29+ scoped MediaStore save.

M7 uses a `dataSync` foreground service with a mandatory ongoing notification.
This improves survival after the user leaves or locks the device but cannot
override force-stop, shutdown, platform time limits, or OEM process policy.
On Android 13 and newer, denied notification permission can hide the drawer
entry even though the system active-apps surface still exposes the foreground
service.
Interrupted paid requests become `OutcomeUnknown` and are never retried
automatically. State these limits in release notes.

## 5. Publish

- Name the tag `android-v<versionName>`.
- Publish only the verified signed APK and its SHA-256 digest.
- Keep release signing material and private smoke inputs local.
- Have Codex record the test and packaging evidence, Claude Code independently
  review it, and the user make the final release decision.
