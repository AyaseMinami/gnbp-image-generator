# Android Sideload Release Guide

Status: M8 release-candidate plan approved; signed-candidate evidence remains pending

This guide covers the signed APK workflow for the first side-loaded Android
edition. It does not authorize a release by itself. The M7 reliability
test/review gate has passed, but a tag or public APK still requires the API 37
16 KB device evidence and signed-candidate acceptance recorded in the
development plan.

## 1. Keep Signing Material Outside The Repository

- Create or select the release keystore through Android Studio's **Build >
  Generate Signed Bundle / APK > APK** workflow.
- Store the keystore outside this checkout and outside cloud-synchronized
  folders unless it is protected by an approved secret-management process.
- Never add keystore paths, aliases, passwords, exported certificates, or
  signing Gradle properties to the repository, CI variables, issue text, or
  build logs.
- Do not add a production `signingConfig` or signing secrets to the checked-in
  Gradle build. CI may assemble an unsigned release variant and validate a
  separately supplied APK, but it never receives the release key.
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
   .\gradlew.bat check assembleDebugAndroidTest
   ```

   `check` includes lint, offline unit tests, the network chokepoint, and the
   debug APK ZIP/ELF guards. M8 must add an explicit release-candidate check;
   before that task exists and passes against the exact final signed APK, this
   guide is not a release authorization.
3. Confirm the blocking device matrix is green on API 26, 29, 33, and 36.
   API 37 16 KB remains a non-blocking hosted-CI signal, but a successful
   Firebase Test Lab or physical 16 KB device run is mandatory before any tag or
   public distribution.
4. Run a real-provider smoke only with explicit authorization. Never include
   `test_api.txt`, provider reports, private endpoints, prompts, or returned
   images in the release artifact or commit.
5. Review the current parity limits and M7 known limits in
   [`../contracts/PLATFORM-PARITY.md`](../contracts/PLATFORM-PARITY.md). In
   particular, the first-release decision accepts app-private plaintext prompts
   and sanitized reference display names. Confirm that the release remains
   non-debuggable and that release notes disclose residual root/physical-
   extraction exposure. Backup and device transfer must remain disabled.
6. Confirm concurrent retry is engine-level idempotent and a pending
   permission-gated submission survives Activity recreation before preparing
   the candidate.

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
The M8 candidate validator must also parse every packaged arm64 ELF program
header from this exact signed APK and reject any `LOAD.p_align < 0x4000`.

## 4. Sideload Acceptance Check

The debug and release variants use the same application ID but different
signatures. If the debug build is installed, uninstall it first. Uninstalling
also removes the app-private database and Android Keystore alias, so the clean
release installation must re-enter its API profiles and keys.

Install the candidate on a clean test device:

```powershell
adb install <signed-apk>
```

Verify both the default Chinese resources and the English resources by changing
the system locale. In light and dark system themes, exercise:

- create, edit, select, and delete an API profile;
- strict TLS rejects HTTP, invalid certificates, and hostname mismatch by
  default; each approved compatibility mode works only after exact-profile and
  exact-authority opt-in, and an authority change resets the binding to strict;
- create and apply a prompt preset;
- text-to-image and reference-image generation;
- batch queue, cancellation, explicit retry, and `OutcomeUnknown` warning;
- Gallery preview, Android share, and reuse as a reference image;
- both notification-permission grant and deny paths; denial must not prevent
  generation or foreground execution, and audible/silent settings and task
  diagnostics must remain coherent;
- background a multi-task queue, lock the screen, enter Doze long enough to
  cover a real long-running request, and confirm the ongoing foreground
  notification remains free of prompts, endpoints, URIs, and keys;
- interrupt the process during one running and one queued task, then relaunch:
  the running task must become `OutcomeUnknown`, the queued task may resume, and
  neither request may be retried automatically;
- interrupt after a fake/offline provider response is staged or after its
  MediaStore receipt is journaled; relaunch must recover the result association
  without making another provider request;
- cancel an active request from Tasks and confirm the transport call stops and
  the foreground service exits after all work becomes terminal;
- activity rotation while editing and while viewing every main screen;
- Activity recreation while the API 26-28 legacy-media or API 37 LAN permission
  dialog is pending; a granted result submits exactly once with the original
  references, while denial leaves the draft recoverable;
- API 26 legacy save permission and API 29+ scoped MediaStore save.

Capture release-build logcat during profile save, generation, cancellation,
retry, background execution, and restart. Scan it for the exact test API key,
authorization headers, complete Gemini URLs, private endpoints, prompts, and
private content URIs. Any match blocks release. Use only deliberately controlled
test values in this scan and do not preserve the raw log as a release artifact.

Exercise non-graceful foreground-service destruction while work is active and
record whether the current bounded shutdown fallback causes visible main-thread
freezing. The fallback's removal is assigned to M9 unless acceptance exposes a
release-blocking failure.

M7 uses a `dataSync` foreground service with a mandatory ongoing notification.
This improves survival after the user leaves or locks the device but cannot
override force-stop, shutdown, platform time limits, or OEM process policy.
On Android 13 and newer, denied notification permission can hide the drawer
entry even though the system active-apps surface still exposes the foreground
service.
Interrupted paid requests become `OutcomeUnknown` and are never retried
automatically. Release notes must also disclose the documented foreground-work
limits and the narrow MediaStore recovery window that can create one duplicate
local image without repeating the provider request or charge.
Release notes must also disclose the acknowledged trust-all/cleartext modes,
the app-private plaintext prompt/reference-name decision, and the exact real
provider validation scope. Unless a separately authorized Gemini smoke test is
completed, state that Gemini has offline contract coverage but no real-device
end-to-end provider evidence; do not label it experimental in the application.

## 5. Publish

- Name the tag `android-v<versionName>`.
- Publish only the verified signed APK and its SHA-256 digest.
- Keep release signing material and private smoke inputs local.
- Have Codex record the test and packaging evidence, Claude Code independently
  review it, and the user make the final release decision.
