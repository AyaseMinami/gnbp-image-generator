# Android Development Plan

Status: M8 release-candidate plan approved; implementation and public release evidence remain pending
Date: 2026-07-20

## 1. Objective

This plan delivered an Android edition of GNBP Image Generator as a native
mobile application through M7. The Android edition preserves the product's generation capabilities and
observable behavior where that behavior still makes sense on mobile. It is not
a source-level port of the Python/PySide6 implementation.

The Android project lives in `android/` as a self-contained Gradle project. The
existing Windows application remains at the repository root. Shared behavior
specifications and redacted fixtures live outside either platform
implementation.

The platform choice and rejected alternatives are recorded in
[`adr/0001-native-android-implementation.md`](adr/0001-native-android-implementation.md).
That ADR was accepted by the user during M0.

## 2. Constraints

- GNBP remains a BYOK API client. It does not provide models, quota, keys, or a
  hosted generation service.
- Automated tests must be offline and must never call a real image API.
- API keys, signing files, local endpoints, generated images, and build output
  must never be committed.
- Desktop behavior must not regress merely to simplify the Android
  implementation.
- Mobile UI and platform behavior may differ when direct parity would produce a
  poor Android experience.
- HTTPS certificate verification is secure by default. Relay compatibility is
  governed by the accepted transport-security specification and exact
  profile/authority binding checks.
- API 37 local-network permission, certificate transparency, ECH, and localhost
  behavior are part of the relay design rather than release hardening.
- Paid generation requests must not be retried automatically when their outcome
  is unknown.

## 3. Delivery Targets

M1-M7 and their independent review gates are complete. M8 is the active
release-candidate milestone. The targets and original
post-M0 estimates below are retained as the approved delivery record; they do
not imply that an Android tag or signed public APK has been released. Remaining
release evidence is defined in Section 15 and
`ANDROID-SIDELOAD-RELEASE.md`.

All estimates in this section start after M0 decisions are resolved. They assume
the selected relay TLS behavior is technically viable and exclude external
review, store approval, and unresolved product-decision time.

### 3.1 Technical Preview

The first preview proves the complete device-to-provider-to-gallery path:

- one Gemini-compatible and one OpenAI-compatible profile;
- text-to-image generation for both provider types;
- GPT-compatible image editing with selected reference images;
- Android Photo Picker input;
- foreground-only execution with one running task;
- preview, MediaStore save, and Android share action;
- offline request and response contract tests;
- a reproducible debug APK build.

Estimated effort: 5-8 working days.

### 3.2 Usable Sideloaded MVP

- profile create, update, select, and delete;
- encrypted API-key storage;
- prompt presets;
- multiple reference images;
- batch submission and a persistent task list;
- one or two concurrent tasks;
- queued-task cancellation and explicit failed-task retry;
- result preview, save, share, and reuse as a reference image;
- basic completion notifications;
- offline unit, adapter, persistence, and ViewModel tests.

The MVP may document that work stops if Android kills the process. Reliable
background continuation is a separate delivery target.

Estimated total effort after M0: 4-6 weeks for one developer already familiar
with Kotlin and Compose.

### 3.3 Reliable Release

- persistent task and result history;
- controlled foreground work and user-visible ongoing notifications;
- process-death reconciliation without silently duplicating paid requests;
- durable access to selected input images;
- cancellation of active HTTP calls;
- network, storage, low-memory, rotation, and process-restart tests;
- signed-release procedure, privacy documentation, and release checklist. The
  signed candidate and public-release evidence remain a gate after M7.

Estimated total effort after M0: 7-10 weeks. Store review time is not included.

### 3.4 Later Parity Work

- PNG `parameters` metadata interoperability with the desktop application,
  including a PNG `tEXt` chunk implementation or evaluated library and
  cross-platform golden-file tests;
- configurable concurrency beyond the mobile-safe default;
- advanced batch operations and task cleanup;
- configuration import and export;
- tablet layouts and additional themes;
- optional user-selected output folders;
- platform-appropriate replacements for desktop clipboard and drag-and-drop
  workflows.

## 4. Repository Layout

The implemented repository layout is:

```text
gnbp-image-generator/
|-- android/                      # Self-contained Gradle project
|   |-- app/
|   |-- gradle/
|   |-- build.gradle.kts
|   |-- settings.gradle.kts
|   `-- gradlew(.bat)
|-- contracts/                    # Redacted cross-platform fixtures/specs
|   |-- gemini/
|   |-- openai/
|   `-- metadata/
|-- config/ core/ ui/ tests/      # Existing Windows implementation
|-- docs/
`-- .github/workflows/
```

The two implementations share behavior specifications, not source code. The
desktop code remains at the repository root rather than moving into a
`desktop/` directory.

CI workflows are path-filtered:

- desktop tests run for desktop paths, shared contracts, or desktop workflow
  changes;
- Android tests run for `android/**`, shared contracts, or Android workflow
  changes;
- documentation-only changes do not build both applications unless they modify
  a contract.

Desktop and Android versions are independent. `app_info.py` remains the desktop
version source. Android uses Gradle `versionName` and monotonic `versionCode`.
Release tags use platform prefixes such as `desktop-v9.2.0` and
`android-v0.1.0`.

## 5. Implemented Android Stack

- Kotlin;
- Jetpack Compose and Material 3;
- coroutines and `Flow` for asynchronous state;
- OkHttp with structured JSON serialization for configurable provider URLs;
- Room for profiles, prompts, tasks, and result history;
- DataStore for lightweight application settings;
- Android Keystore-backed encryption for API keys;
- Photo Picker and `ContentResolver` for reference images;
- MediaStore for generated results;
- Coil for previews and thumbnails;
- Android string resources from the first scaffold; no user-facing text is
  hard-coded in Compose code;
- an application-scoped `GenerationEngine` controlled by a non-exported
  `dataSync` foreground service for M7 reliable work;
- JUnit, MockWebServer, Room tests, and Compose UI tests.

Dependency versions are held in the Gradle version catalog. The implemented app
uses `minSdk 26`, `compileSdk 37`, and `targetSdk 37`; Android identity and
version values remain single-sourced in `android/app/build.gradle.kts`.

## 6. Architecture

### 6.1 Generation Module

The primary deep module is `GenerationEngine`. Its external interface is small
and exposes product behavior rather than transport or Android details:

```text
enqueue(BatchRequest) -> List<TaskId>
observeTasks() -> Flow<List<TaskState>>
cancel(TaskId)
```

Its implementation owns validation, batch expansion, immutable task snapshots,
provider selection, concurrency, state transitions, result saving, metadata
sanitization, and completion events.

Task states are typed and follow this minimum model:

```text
Queued -> Running -> Succeeded | Failed | Cancelled | OutcomeUnknown
```

`OutcomeUnknown` is required when a paid request may have reached the provider
but the application cannot determine the response after interruption. It must
never trigger an automatic retry.

### 6.2 Internal Seams And Adapters

- `ImageGenerationProvider.generate(request) -> GeneratedImage`
  - Gemini production adapter;
  - OpenAI-compatible production adapter;
  - offline fake adapter.
- `GeneratedAssetStore.save(image, metadata) -> AssetRef`
  - MediaStore production adapter;
  - in-memory or temporary test adapter.
- `ProfileStore`
  - Room plus encrypted-secret production adapter;
  - in-memory test adapter.

Photo Picker, sharing, notifications, and navigation remain Android UI
implementation details. They must not be collected into a large generic
`Platform` interface.

### 6.3 Mobile Screens

The initial navigation model is:

- Generate: profile, prompt, image parameters, reference images, and submit;
- Tasks: queue state, timing, cancel, retry, and reuse parameters;
- Gallery: generated results, preview, share, and reuse as reference;
- Settings: profiles, prompt presets, security options, and notifications.

Phone layouts use single-column screens and Android navigation. Desktop tables,
right-click menus, window sizes, Explorer actions, and keyboard shortcuts are
not parity requirements.

## 7. Desktop Behavior Mapping

| Behavior | Desktop reference | Android destination |
| --- | --- | --- |
| Profiles, prompts, settings | `config/config_mgr.py` | Room/DataStore repositories |
| Gemini request and response | `core/api_client.py` | `GeminiProvider` adapter |
| GPT generation and editing | `core/gpt_client.py` | `OpenAiProvider` adapter |
| Queue and concurrency | `core/task_queue.py` | `GenerationEngine` |
| Image preparation and metadata | `core/utils.py` | media preparation and asset store |
| User workflow | `ui/main_window.py` | Compose feature screens |

Behavior to preserve:

- custom provider base URL, key, and model;
- Gemini aspect ratio and size request semantics;
- GPT generation without references and edit requests with references;
- one immutable generation snapshot per queued item;
- collision-safe result identity;
- API keys excluded from generated metadata;
- reference-image metadata reduced to non-private display names;
- configuration migrations fill new defaults without replacing user values.

Provider behavior resolved explicitly rather than copied silently:

- the desktop Gemini adapter sends `BLOCK_NONE` for four safety categories;
- M0 decision 10 preserved `BLOCK_NONE` for the first side-loaded Android
  edition, M2 implemented it, and any application-store distribution requires a
  new content-policy review.

Behavior to change deliberately:

- filesystem paths become durable asset references or Android content URIs;
- plain-text API keys become encrypted secrets;
- trust-all TLS is never enabled globally; any platform-wide cleartext opt-in
  required for arbitrary HTTP relay hosts is governed by the reviewed transport
  specification and exact application-layer binding checks;
- mobile concurrency defaults to one and is initially capped at two;
- task status and errors become typed values rather than display strings;
- Android system actions replace Explorer, `os.startfile`, `winsound`, desktop
  clipboard, and drag-and-drop behavior.

## 8. Implementation Milestones

### M0 - Decisions And Contracts

- accept or reject the proposed native Android platform ADR;
- resolve the release package ID, minimum Android version, distribution channel,
  application languages, TLS compatibility policy and delivery milestone,
  background-execution requirement, Gemini safety behavior, and metadata
  interoperability requirement;
- produce a separately reviewed relay TLS compatibility specification before M2
  if invalid certificates or cleartext relay endpoints must be supported;
- capture redacted Gemini and OpenAI-compatible success and failure fixtures;
- write a platform parity matrix;
- have the plan and contract shapes independently reviewed.

Exit criteria: no secret-bearing fixture; user approval of unresolved product
decisions; review findings dispositioned.

### M1 - Android Scaffold

- create the Kotlin/Compose Gradle project under `android/`;
- add version catalog, static analysis, unit tests, and debug build;
- add Android-specific ignore rules;
- add a path-filtered Android CI workflow;
- add path filters to the existing desktop workflow so Android-only changes do
  not install Python/PySide6 or run desktop tests.

Exit criteria: clean checkout can run unit tests and build a debug APK without
accessing a real image provider; an Android-only change selects only Android CI,
while a shared-contract change selects every affected platform workflow.

### M2 - Provider Contract Slice

- review and accept
  [`ANDROID-RELAY-TRANSPORT-SECURITY.md`](ANDROID-RELAY-TRANSPORT-SECURITY.md)
  before production adapter coding;
- define typed requests, results, and errors;
- implement Gemini and OpenAI-compatible adapters;
- cover generation, editing, blocked responses, HTTP failures, malformed JSON,
  timeouts, and cancellation with MockWebServer;
- ensure logs redact keys and authorization headers;
- implement and test the user-approved Gemini safety behavior;
- implement the approved relay TLS policy from its reviewed specification and
  test both secure defaults and every supported compatibility mode.

Exit criteria: all provider behavior is exercised through offline adapter tests;
the selected real-world relay certificate cases are supported by design before
the end-to-end workflow begins.

### M3 - Profiles And Secure Persistence

- implement profile and prompt storage;
- encrypt API keys with a Keystore-backed key;
- implement settings and persistence migrations;
- add backup behavior that excludes or safely handles secrets.

Exit criteria: CRUD, migration, encryption, and restart tests pass.

### M4 - Media Input And Output

- connect `LinkProperties.getNat64Prefix()` to the transport address classifier
  so DNS64-wrapped private destinations cannot bypass per-profile LAN policy;
  add the well-known `64:ff9b::/96` fallback and test both private and public
  embedded IPv4 destinations;
- integrate Photo Picker and durable URI handling;
- implement bounded image decoding, resize, and compression;
- save results through MediaStore;
- implement and test both storage paths required by `minSdk 26`: permission and
  legacy insert behavior on API 26-28, and scoped MediaStore behavior on API 29+;
- implement preview, share, and reuse-as-reference behavior;
- define cleanup rules for temporary input copies: incomplete copies become
  eligible after one hour, unreferenced durable copies after seven days, and
  retained draft/task IDs are never removed; M5 owns startup cleanup after its
  repositories can supply the retained-ID set.

Exit criteria: instrumentation tests cover picker results, revoked access,
large images, save failures, and external deletion. Host CI verifies 16 KB APK
page alignment, and the blocking device matrix passes on API 26, 29, 33, and
36.

API 37.0 currently provides only `google_apis_ps16k` system images. The current
hosted-runner/image combination remains offline even with Ubuntu KVM enabled;
run
[`29646096937`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29646096937)
is the reproducible evidence. Keep that API 37 16 KB job as a non-blocking
compatibility signal until the hosted image becomes usable. Before any tag
release or application-store submission, the repository maintainer acting as
release owner must arrange one successful API 37 16 KB
`connectedDebugAndroidTest` run through Firebase Test Lab or a physical 16 KB
device. Codex records the execution evidence and Claude Code independently
reviews it before the user approves release.

### M5 - End-To-End Generation Workflow

- implement `GenerationEngine` with fake providers first;
- build Generate and Tasks screens;
- add immutable batch snapshots, concurrency control, queued cancellation, and
  explicit retry;
- prepare reference images off the main thread before enqueue, copy their bytes
  into the immutable task snapshot, and transfer ownership to the task so draft
  cleanup cannot invalidate queued or running work;
- perform draft removal and cleanup file I/O off the main thread;
- on startup, reconcile any task left in `Running` as `OutcomeUnknown` and never
  retry it automatically, even in the foreground-only MVP;
- keep safe `Queued` work queued across restart and rebuild its execution
  snapshot from persisted request metadata, the current encrypted profile, and
  app-owned reference copies; use the same rebuild path for explicit retry;
- keep the public `GenerationEngine` contract product-level: no Android `Uri`,
  `File`, persistence entity, or secret-bearing profile objects;
- connect real adapters only after fake-driven workflow tests pass.

Exit criteria: a complete fake-provider workflow passes UI tests, followed by a
manual opt-in smoke test using developer-supplied credentials.

### M6 - MVP Completion

- add Gallery and Settings screens;
- add completion notifications and user-facing error diagnostics;
- finish accessibility, rotation, dark theme, and low-memory checks;
- ship the user-approved language resources with fallback behavior and no
  hard-coded user-facing strings;
- prepare signed side-loaded APK documentation.

Exit criteria: MVP checklist passes on the agreed Android device matrix; all
automated tests remain offline.

### M7 - Reliable Background Release

- move the single generation-engine owner from the Activity/ViewModel lifecycle
  to an application-scoped runtime controlled only by a non-exported `dataSync`
  foreground service;
- start the service before accepting enqueue, cancel, or retry commands and keep
  an ongoing, content-free notification visible while commands or tasks are
  active;
- request sticky service restart and resume durable `Queued` work after process
  recreation, while reconciling every interrupted `Running` request as
  `OutcomeUnknown` without automatic retry;
- cancel and join active provider work before an orderly service timeout or
  shutdown writes its final interruption state;
- atomically stage each successful provider response in app-private storage and
  journal the published MediaStore receipt before marking the Room task
  successful, so restart can finish local saving without another provider call;
- validate that task-owned reference copies and generated MediaStore results
  remain addressable across Activity and process recreation;
- add a blocking ELF program-header check that rejects arm64 native libraries
  whose `LOAD` segments have `p_align < 0x4000`;
- complete privacy and signed side-load documentation. Store-specific policy
  work remains out of scope until a store distribution channel is approved.

Exit criteria: interruption scenarios cannot silently lose successful results or
automatically duplicate an uncertain paid request.

### M8 - Android 0.1.0 Release Candidate

M8 freezes the first-release feature set. PNG metadata interoperability,
configuration import/export, alternate output folders, clipboard workflows,
tablet layouts, and other parity additions remain out of scope until a signed
`0.1.0` candidate passes its release gate.

- refactor the APK page-alignment and arm64 ELF program-header checks so the
  exact final signed APK, not only `app-debug.apk`, must pass ZIP 16 KB alignment
  and every arm64 `LOAD.p_align >= 0x4000`;
- add an explicit release-candidate verification entry point that assembles the
  unsigned release variant, runs lint and offline tests, and validates a
  caller-supplied signed APK without receiving signing paths, aliases, or
  passwords;
- keep release signing material outside the repository and CI; the user owns
  the signing identity and an independently stored recovery backup;
- make explicit retry idempotent at the engine/persistence boundary, not only in
  the UI: concurrent retry commands for one source task may create at most one
  direct replacement task, and subsequent retries must target the replacement;
- preserve a pending permission-gated submission across Activity recreation,
  including API 26-28 legacy media permission and API 37 LAN permission flows,
  without losing selected reference ownership or submitting twice;
- retain non-debuggable release behavior. The first release accepts plaintext
  prompts and sanitized reference display names in the app-private Room
  database because backup and device transfer are disabled; release notes must
  disclose that root access or physical extraction can still expose them;
- build and verify a signed `versionCode = 1`, `versionName = 0.1.0` APK,
  recording only its public signing-certificate digest, APK SHA-256, and
  redacted validation results;
- run the complete instrumentation suite on API 37 / 16 KB locally as a
  preliminary signal, then obtain the approved successful Firebase Test Lab or
  physical 16 KB device result before release;
- execute the clean-device acceptance guide, including strict and compatible
  TLS behavior, runtime log redaction, notification grant/deny paths, and a
  screen-off/Doze long-running queue;
- disclose that Gemini has offline contract coverage but no real-device
  end-to-end provider evidence unless a separately authorized Gemini smoke test
  is completed before publication. Do not label the implemented provider
  experimental in the application;
- accept only fixes discovered by release acceptance. New product features stay
  frozen until after the candidate is approved.

Exit criteria: retry and permission-recreation regressions are closed by
automated tests; the exact signed candidate passes signing, ZIP/ELF, privacy,
TLS, logcat, clean-install, background, and API 37 / 16 KB evidence review;
Claude Code independently verifies the evidence and the user approves M8. M8
approval does not authorize `dev -> main`, a tag, or a GitHub Release.

### M9 - Data Lifecycle And Reliability Follow-Up

- define bounded terminal-task and task-owned-reference retention, plus
  individual deletion and coordinated bulk cleanup that never silently deletes
  public MediaStore results;
- remove the bounded main-thread `runBlocking` service-shutdown fallback while
  retaining application-scoped cancellation and join semantics;
- apply concurrency-setting changes to an active runtime without waiting for an
  idle restart;
- reserve disjoint notification-ID namespaces;
- investigate deduplicating the narrow MediaStore receipt window without ever
  repeating a provider request or losing generated bytes.

M8 acceptance must deliberately exercise non-graceful service destruction and
record whether the current bounded shutdown fallback causes a visible freeze.
That observation does not replace the M9 implementation.

## 9. Test Strategy

- Contract tests: redacted provider payloads and responses shared as fixtures.
- Module tests: `GenerationEngine` observed only through its external interface.
- Adapter tests: MockWebServer for HTTP and fakes for storage and secrets.
- Persistence tests: Room migrations, process restart, and task reconciliation.
- Media tests: content URI access, large-image memory behavior, MediaStore, and
  metadata sanitization.
- UI tests: primary Generate, Tasks, Gallery, and Settings workflows.
- Manual tests: a small opt-in provider smoke suite that is never part of CI and
  never records credentials or private endpoints.

Desktop tests continue to run independently. A shared contract change is not
complete until every affected platform test passes.

## 10. Security And Privacy

- Never log API keys, authorization headers, full Gemini request URLs, or private
  content URIs.
- Store API keys encrypted at rest using a Keystore-backed key.
- Use normal TLS verification by default.
- Do not implement a trust-all network client without a separately reviewed and
  user-approved compatibility specification completed before M2 adapter work.
- Keep API 37 CT and ECH enabled globally, distinguish LAN permission from
  localhost behavior, and scope any private-certificate exception to one
  profile and exact authority.
- Treat relay compatibility as an MVP input, not release hardening: the Android
  edition is not usable for its target audience unless the approved endpoint and
  certificate cases work by M5.
- Make Gemini safety settings explicit and testable; do not copy `BLOCK_NONE`
  silently from the desktop implementation.
- Re-review `BLOCK_NONE` before distribution through any application store,
  including domestic stores rather than only Google Play.
- Make it clear that prompts and reference images are sent to the provider
  configured by the user.
- Strip keys and private URI/path details from exported metadata.
- Keep Android signing material outside Git and CI logs.
- Prefer Photo Picker over broad media permissions.

## 11. Dual-Agent Collaboration Protocol

### 11.1 Roles

- Codex owns design, planning, implementation, test execution, and the initial
  self-review.
- Claude Code independently reviews plans, architecture decisions, milestone
  diffs, tests, security behavior, and release readiness.
- The user is the decision authority for unresolved product choices and material
  reviewer disagreements.

Claude Code is a reviewer, not an automatic source of truth. Codex is the
implementer, not the final authority on its own work.

### 11.2 Evidence Standard

Every review finding should include:

- severity and affected requirement;
- exact file and line or design section;
- concrete failure mode or violated invariant;
- reproduction steps or technical evidence where possible;
- a proposed correction or acceptance criterion.

Codex records one disposition for each material finding:

- Accepted: evidence is valid; implementation or plan is changed and a
  regression test is added where practical.
- Rejected: evidence does not hold; the rejection includes a technical rationale.
- Deferred: valid but outside the current milestone; scope and revisit point are
  recorded explicitly.
- Escalated: both agents retain materially different conclusions and the user
  decides before implementation crosses the disputed seam.

Neither agent should accept a claim solely because the other agent made it.
Conversely, disagreement alone is not a reason to reject a finding.

### 11.3 Review Gates

1. Plan gate: review this plan and the unresolved decisions before scaffolding.
2. Architecture gate: review interfaces and fixture contracts before production
   adapters are implemented.
3. Slice gate: review each vertical-slice diff after its tests pass.
4. Milestone gate: run the full affected test suites and review requirement
   coverage before starting the next milestone.
5. Release gate: independent security, privacy, packaging, and regression review.

Reviews must reference a fixed commit or diff. Claude Code should not edit files
under active Codex implementation unless the user explicitly changes its role;
this prevents review evidence from being mixed with implementation changes.
The user's current direct instructions always take precedence over role or
workflow descriptions in this document.
Material decisions are retained in project documentation or an ADR. Raw chat
transcripts and routine review exchanges do not need to be committed. Long-form
review artifacts may be saved when they are needed for reliable transfer or
long-term traceability.

## 12. Git And Delivery Discipline

- Start Android feature branches from `dev`.
- Keep Android scaffolding, CI, provider adapters, persistence, media, UI, and
  release work in reviewable commits.
- Do not combine desktop refactors with Android feature commits.
- Keep `main` releasable; merge verified work from `dev` using the established
  repository workflow.
- Do not commit generated APK/AAB files, Gradle caches, signing stores,
  `local.properties`, credentials, private endpoint probes, or generated images.

## 13. Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| Provider relay behavior differs from fixtures | Expand redacted contract fixtures before changing adapters |
| Android kills a long request | Separate foreground MVP from persistent reliable execution |
| An interrupted paid request is duplicated | Use `OutcomeUnknown`; require explicit user retry |
| Content URI permission expires | Persist permission where supported or copy into controlled storage |
| Multiple 4K/base64 images exhaust memory | Bounded decode, stream-oriented I/O, low concurrency, memory tests |
| Insecure relay certificates conflict with mobile security | Decide scope in M0; review the compatibility specification before M2; validate it by M5 |
| Gemini safety behavior conflicts with provider or store policy | Make the safety request explicit and tie it to the distribution decision |
| Hard-coded UI language blocks part of the target audience | Choose languages in M0 and use Android string resources from M1 |
| Desktop and Android behavior drifts | Shared fixtures, parity matrix, path-filtered tests, cross-platform review |
| Rapid duplicate retry creates multiple paid replacements | Enforce one persisted direct replacement per source task at the engine boundary and stress concurrent retry in M8 |
| Hosted API 37 16 KB image does not boot | Keep a non-blocking compatibility signal, enforce APK alignment statically, and require FTL or physical-device evidence before release |
| Reviewers defer to each other without evidence | Mandatory finding evidence and explicit disposition protocol |

## 14. Resolved M0 Decisions

1. Application ID: `io.github.ayaseminami.gnbp`; publisher: `AyaseMinami`.
2. `minSdk 26`; target the latest stable Android API at implementation time.
   The blocking hosted device matrix tests API 26, 29, 33, and 36 when M4
   introduces platform storage behavior and again at the M6 device-matrix
   gate. API 37 16 KB remains a non-blocking compatibility signal because its
   only available hosted system image does not boot in the verified runner
   combinations. M1 uses host-side unit tests, lint, and APK assembly only.
3. Publish the first edition as a side-loaded APK on GitHub Releases. Reconsider
   Google Play and domestic stores after the reliable-release milestone.
4. The technical preview and side-loaded MVP guarantee only foreground
   generation. They do not promise process-death recovery. Android may still
   kill the process after the user leaves or locks the device; after restart,
   affected paid requests become `OutcomeUnknown` and are never retried
   automatically.
5. Use strict TLS verification by default. The MVP supports an explicit,
   per-profile, per-host compatibility mode. Prefer custom CA/pinning; allow
   trust-all or cleartext HTTP only as reviewed, warning-backed fallbacks. The
   compatibility specification remains an M2 prerequisite.
6. The first release stores generation parameters in Room and does not require
   desktop-compatible PNG `tEXt` metadata. Cross-platform PNG metadata is later
   parity work.
7. The first release does not import desktop configuration.
8. Accept ADR 0001 and native Kotlin/Compose. There is no planned iOS edition in
   the next 12 months.
9. Ship Chinese and English resources, follow the system locale, and use Chinese
   in the default `values/` resources. English uses `values-en/`; every other
   unsupported locale intentionally falls back to Chinese.
10. The side-loaded first edition preserves the desktop Gemini `BLOCK_NONE`
    request behavior. Content policy must be reviewed again before distribution
    through Google Play or any domestic application store.

## 15. Implementation And Gate Record

1. M1 scaffold review passed at commit `d32d897` with no blocking findings.
2. The independent architecture review accepted
   [`ANDROID-RELAY-TRANSPORT-SECURITY.md`](ANDROID-RELAY-TRANSPORT-SECURITY.md),
   including its global-cleartext tradeoff and API 37 behavior.
3. The typed transport and provider contract slice now has offline tests and a
   mechanical CI check that keeps networking construction inside the provider
   transport module.
4. M2 slice review passed at commit `ab8282e`.
5. M3 secure-persistence review passed at commit `e67bdb8` with no blocking
   findings.
6. M4 media input/output, API 26-28 and API 29+ MediaStore paths, bounded image
   preparation, and NAT64 discovery passed independent code review at commit
   `61c25fd` with no blocking code findings. The user redefined the blocking M4
   device gate as API 26, 29, 33, and 36 after API 37 16 KB stayed offline on
   both macOS ARM and Ubuntu x86_64/KVM hosted runners. Ubuntu/KVM evidence is
   run
   [`29646096937`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29646096937);
   the failure happened before instrumentation tests began. API 37 remains a
   non-blocking compatibility job rather than being removed.
7. The M4 APK baseline contains two vendor native libraries in four ABIs. All
   eight packaged `.so` entries pass `zipalign -c -P 16`, and the two arm64-v8a
   libraries have ELF `LOAD` segment `p_align = 0x4000`. CI now treats the APK
   alignment check as blocking. That task verifies APK package alignment only;
   M7 adds a second blocking guard that parses every packaged arm64 ELF program
   header and rejects any `LOAD` segment below `p_align = 0x4000`. On API 26-29,
   Android does not expose a network-specific NAT64 prefix; the classifier can
   recognize the well-known `64:ff9b::/96` prefix but not a provider-specific
   prefix on those OS versions.
8. The redefined M4 gate passed in CI run
   [`29649381772`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29649381772):
   host verification and API 26/29/33/36 instrumentation passed, while the
   explicitly non-blocking API 37 16 KB compatibility job reproduced the known
   pre-instrumentation boot timeout. Claude Code independently reviewed the
   evidence with no blocking findings, and the user formally released M4 on
   2026-07-19, making M5 the active milestone at that point.
9. The first M5 generation-workflow slice now provides the `GenerationEngine`,
   Room task persistence/migration `2 -> 3`, immutable reference preparation,
   cancellation/retry/`OutcomeUnknown` transitions, and Generate/Tasks Compose
   screens with an offline fake-provider instrumentation workflow. Follow-up
   hardening keeps the engine contract free of Android/persistence types,
   resumes safe queued work after restart, and enables explicit retry from only
   persisted task metadata plus current profile/reference adapters. The slice
   passed host verification and the blocking API 26/29/33/36 device matrix in
   final run
   [`29674827413`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29674827413).
   Claude Code's first review confirmed the state-machine and persistence
   invariants, then required reference ownership to move before enqueue and the
   Compose workflow to use the real submit-button click path. Commits `4584136`
   and `0fadfbd` addressed both findings, and the independent re-review closed
   them. At that point, profile editing remained assigned to M6 and reliable
   background execution remained assigned to M7.
10. Claude Code independently closed both M5 review findings. A manually
    authorized, single-request OpenAI-compatible HTTPS relay smoke test then
    passed through the Android provider and transport implementation. The
    opt-in Gradle task remains excluded from default tests and CI; its reports
    contained no API key, endpoint, model, prompt, provider message, or image
    bytes. Claude Code independently verified the ignored smoke report and the
    user formally released M5 on 2026-07-19, making M6 the active milestone at
    that point.
11. M6 closes the predictable blank-prompt ownership case by disabling Submit
    until the prompt is non-blank while retaining engine-side validation. The
    remaining permission-dialog recreation caveat stays deferred because it
    does not delete the draft asset or risk an automatic paid retry.
12. The M6 implementation adds profile and prompt CRUD, every reviewed
    binding-scoped transport mode, Gallery preview/share/reference reuse,
    localized typed diagnostics, opt-in completion notifications, bounded
    thumbnails, state-restoration UI coverage, and the signed side-load guide.
    The local gate passed 100 offline Android tests, lint with zero errors,
    instrumentation APK compilation, the network-construction chokepoint, APK
    16 KB package alignment, and all 8 desktop tests. CI run
    [`29681176171`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29681176171)
    then passed host verification and the blocking API 26/29/33/36 device
    matrix. The API 37 16 KB job reproduced its approved non-blocking hosted
    image failure.
13. Claude Code independently reviewed the M6 range `daf90cf..a0c5748`,
    confirmed profile-security editing, exact-authority reset behavior,
    certificate and pin validation, notification permission handling, Gallery
    URI ownership, completion-event deduplication, localization, state
    restoration, and secret/report redaction, and reported no blocking or
    security findings. The final CI run
    [`29681595718`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29681595718)
    passed host verification and the blocking API 26/29/33/36 device matrix;
    API 37 reproduced the approved non-blocking hosted-image failure. The user
    formally released M6 on 2026-07-19, making M7 the active milestone at that
    point.
14. The first M7 implementation moves engine ownership into an application
    graph and a private sticky `dataSync` foreground service. Command leases
    start foreground work before accessing the singleton engine; the service
    posts only static progress counts, owns completion notifications, resumes
    durable queued work, and uses an interruption shutdown path that cancels
    and joins provider work before conservatively writing `OutcomeUnknown`.
    Follow-up hardening makes shutdown an application-scoped shared operation,
    preventing cancellation between engine creation and singleton publication
    from leaking a second engine. Successful provider responses are atomically
    staged in a private result journal; a saved MediaStore receipt or staged
    response is recovered after restart without issuing another paid request.
    Host tests cover singleton startup, restart after initialization failure,
    idle versus interrupted shutdown, and active-work accounting. A device test
    starts the real service and verifies the foreground and ongoing notification
    flags. A final two-axis review found that idle shutdown could race a newly
    acquired command lease; `stopIfIdle()` now checks the lease count and enters
    `Stopping` under the same runtime mutex. The result journal also removes
    orphaned atomic-write temporary files on its first background access.
    Targeted concurrency and process-residue tests cover both corrections. The
    existing APK alignment gate now also blocks arm64 ELF `LOAD` alignment below
    16 KB. The final local gate passes 114 offline Android tests, lint with zero
    errors, instrumentation APK compilation, both 16 KB checks, the network
    chokepoint, and all 8 desktop tests. Internal standards and specification
    re-review found no remaining blocker. Final code commit `448efb7` passed
    host verification and the blocking API 26/29/33/36 instrumentation matrix
    in CI run
    [`29689886647`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29689886647).
    The API 37 16 KB job reproduced its approved non-blocking pre-test boot
    timeout, and the workflow concluded successfully. Claude Code independently
    reviewed nine M7 ownership, shutdown, recovery, notification, offline-test,
    and privacy invariants with no blocking or security findings. The user
    formally released M7 on 2026-07-19. A tag or public APK still requires the
    API 37 16 KB physical-device or Firebase Test Lab evidence and the signed
    release acceptance process.
15. Physical-device testing after M7 exposed that the production Keystore key
    correctly required randomized encryption but the cipher supplied its own
    IV. Commit `04e821c` lets Android Keystore generate the AES-GCM IV and adds
    an isolated instrumentation round trip. The fix passed a RedMagicOS 11 /
    Android 16 device test, a real OpenAI-compatible end-to-end generation, and
    the blocking API 26/29/33/36 instrumentation matrix in CI run
    [`29695222860`](https://github.com/AyaseMinami/gnbp-image-generator/actions/runs/29695222860).
    Claude Code independently reviewed the fix with no blocking findings, and
    PR 4 merged M7 into `dev` as commit `594dda5`.
16. The user approved M8 as a feature-frozen Android `0.1.0` release-candidate
    milestone before M9. Concurrent retry idempotency and pending permission
    submission recovery are M8 blockers. The bounded main-thread shutdown
    fallback remains assigned to M9 but receives deliberate M8 acceptance
    observation. The user also accepted app-private plaintext prompts and
    sanitized reference names for the first side-loaded release, conditioned on
    non-debuggable release packaging and explicit residual-risk disclosure.

## 16. Deferred Backlog

The approved M8 blockers no longer appear here. These items are assigned to M9
or later work:

- Define a bounded retention policy for terminal task history and task-owned
  reference copies, then add repository deletion and coordinated asset cleanup.
- Apply a changed maximum-concurrency setting to an already-active runtime. The
  M7 service uses the persisted value when it creates the engine; later changes
  take effect after the current foreground runtime becomes idle and restarts.
- Investigate deduplicating the at-least-once MediaStore recovery window. If the
  process dies after `MediaStore.save` succeeds but before the receipt journal
  is durable, recovery can create one duplicate local gallery image. It never
  repeats the provider request or charge and does not lose the generated bytes.
- Remove the bounded `runBlocking` shutdown fallback from the service main
  thread while preserving shared application-scoped cancellation and join.
- Reserve separate notification-ID namespaces for the foreground notification
  and task-completion notifications instead of accepting the current small
  hash-collision possibility.
