# Android Development Plan

Status: Approved; M1 and M2 transport architecture accepted
Date: 2026-07-17

## 1. Objective

Build an Android edition of GNBP Image Generator as a native mobile application.
The Android edition should preserve the product's generation capabilities and
observable behavior where that behavior still makes sense on mobile. It is not
a source-level port of the Python/PySide6 implementation.

The Android project will live in `android/` as a self-contained Gradle project.
The existing Windows application remains at the repository root during the
initial Android work. Shared behavior specifications and redacted fixtures will
live outside either platform implementation.

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
- HTTPS certificate verification is secure by default. Compatibility behavior
  for relay services requires an explicit design decision before implementation.
- API 37 local-network permission, certificate transparency, ECH, and localhost
  behavior are part of the relay design rather than release hardening.
- Paid generation requests must not be retried automatically when their outcome
  is unknown.

## 3. Delivery Targets

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
- signed release build, privacy documentation, and release checklist.

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

The intended layout after scaffolding is:

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
desktop code will not be moved into a `desktop/` directory during the initial
Android work.

CI workflows will be path-filtered:

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

## 5. Proposed Android Stack

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
- WorkManager or a foreground-work implementation only when the reliable
  release milestone requires it;
- JUnit, MockWebServer, Room tests, and Compose UI tests.

Dependency versions, `minSdk`, and `targetSdk` will be fixed during scaffolding
against the current stable Android toolchain. Dependencies should be held in a
Gradle version catalog.

## 6. Architecture

### 6.1 Generation Module

The primary deep module is `GenerationEngine`. Its external interface should be
small and expose product behavior rather than transport or Android details:

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

Provider behavior requiring an explicit decision rather than silent parity:

- the desktop Gemini adapter sends `BLOCK_NONE` for four safety categories;
- Android handling must be chosen before M2 and aligned with the distribution
  channel, provider behavior, and applicable generated-content policies.

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

- integrate Photo Picker and durable URI handling;
- implement bounded image decoding, resize, and compression;
- save results through MediaStore;
- implement and test both storage paths required by `minSdk 26`: permission and
  legacy insert behavior on API 26-28, and scoped MediaStore behavior on API 29+;
- implement preview, share, and reuse-as-reference behavior;
- define cleanup rules for temporary input copies.

Exit criteria: instrumentation tests cover picker results, revoked access,
large images, save failures, and external deletion.

### M5 - End-To-End Generation Workflow

- implement `GenerationEngine` with fake providers first;
- build Generate and Tasks screens;
- add immutable batch snapshots, concurrency control, queued cancellation, and
  explicit retry;
- on startup, reconcile any task left in `Running` as `OutcomeUnknown` and never
  retry it automatically, even in the foreground-only MVP;
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

- persist and reconcile task state;
- implement controlled foreground work and notifications;
- handle process death and uncertain paid-request outcomes;
- validate URI persistence across restart;
- complete privacy, signing, release, and store documentation as applicable.

Exit criteria: interruption scenarios cannot silently lose successful results or
automatically duplicate an uncertain paid request.

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
| Reviewers defer to each other without evidence | Mandatory finding evidence and explicit disposition protocol |

## 14. Resolved M0 Decisions

1. Application ID: `io.github.ayaseminami.gnbp`; publisher: `AyaseMinami`.
2. `minSdk 26`; target the latest stable Android API at implementation time.
   Test API 26, 29, 33, and the latest stable API when M4 introduces platform
   storage behavior and again at the M6 device-matrix gate. M1 uses host-side
   unit tests, lint, and APK assembly only.
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

## 15. Immediate Next Gate

1. M1 scaffold review passed at commit `d32d897` with no blocking findings.
2. The independent architecture review accepted
   [`ANDROID-RELAY-TRANSPORT-SECURITY.md`](ANDROID-RELAY-TRANSPORT-SECURITY.md),
   including its global-cleartext tradeoff and API 37 behavior.
3. Implement the typed transport and provider contract slice with offline tests
   and a mechanical CI check that keeps networking construction inside the
   provider transport module.
4. Submit a fixed M2 diff for slice review.
