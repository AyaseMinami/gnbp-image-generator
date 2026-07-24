# Platform Parity Matrix

Status: Android M7 implementation and independent review passed; M9 result-library
and task-management work is in progress; signed public release evidence remains
pending

This matrix compares observable product behavior, not screen layout or source
code. The Android edition intentionally replaces desktop filesystem and window
operations with Android content URIs, MediaStore, Photo Picker, sharing,
notifications, and lifecycle-aware background work.

## Implemented Core Behavior

| Behavior | Desktop reference | Android status |
| --- | --- | --- |
| Custom provider URL, key, model, selection, and profile CRUD | `config/config_mgr.py`, `ui/main_window.py` | Implemented with Room-backed profiles and the M6 Settings UI |
| Gemini request, inline image response, blocked response, aspect ratio, size, and `BLOCK_NONE` | `core/api_client.py` | Implemented by `GeminiProvider`; shared redacted fixtures and offline tests passed in M2 |
| OpenAI-compatible JSON generation and multipart editing with references | `core/gpt_client.py` | Implemented by `OpenAiProvider`; shared redacted fixtures and offline tests passed in M2 |
| Prompt preset create, update, select, and delete | `config/config_mgr.py`, `ui/main_window.py` | Implemented with Room persistence and the Generate/Settings UI |
| Multiple reference images and 1536-pixel/JPEG-85 preparation | `core/utils.py`, `ui/main_window.py` | Implemented with Photo Picker, durable app-private copies, bounded decoding, and off-main-thread preparation |
| Immutable batch task snapshots | `ui/main_window.py`, `core/task_queue.py` | Implemented by `GenerationEngine`; queued work and explicit retry rebuild from persisted request data, the current encrypted profile, and task-owned references |
| Batch generation and concurrency control | `core/task_queue.py`, `ui/main_window.py` | Implemented for batches of 1-16 with a mobile-safe concurrency setting of 1-2 |
| Task state, cancellation, explicit retry, deletion, and elapsed/status presentation | `core/task_queue.py`, `ui/main_window.py` | Implemented in the persistent Tasks screen with selection, confirmed bulk lifecycle actions, and active cancellation through the provider path; uncertain outcomes remain protected |
| Collision-safe generated-image identity | `core/utils.py` | Implemented for scoped and legacy MediaStore paths with full UUID identity |
| Preview and generated-result history | `ui/main_window.py` | Implemented with an independent Room generated-result library, Gallery thumbnails, and preview |
| Reuse task parameters | `ui/main_window.py` | Implemented from persisted Tasks entries |
| Reuse generated result as a reference image | `ui/main_window.py` | Implemented by copying the MediaStore result into the durable reference store |
| User-visible completion behavior | `ui/main_window.py` | Implemented with Android completion notifications and optional sound instead of `winsound` |
| Chinese UI with an English option | Desktop Chinese UI and bilingual README | Implemented as Chinese fallback resources plus `values-en`; every other unsupported locale intentionally falls back to Chinese |
| Configuration migrations preserve existing values and add safe defaults | `config/config_mgr.py` | Implemented with Room schema migrations and DataStore default/clamping behavior |

## Android-Specific Reliability And Security

These behaviors deliberately improve on or replace desktop behavior rather than
copy it:

| Behavior | Android status |
| --- | --- |
| API-key storage | AES-256-GCM ciphertext in Room with an Android Keystore-backed key; editing never displays the saved key |
| TLS defaults and relay compatibility | System-verified TLS by default; custom CA, pinned certificate, acknowledged trust-all, and acknowledged cleartext modes are isolated to an exact profile/authority |
| LAN policy | Explicit per-profile opt-in, normalized IP/DNS classification, NAT64 handling where Android exposes the active prefix, and API 37 `ACCESS_LOCAL_NETWORK` permission handling |
| Paid-request uncertainty | A request that may have been transmitted becomes `OutcomeUnknown` and is never retried automatically |
| Reliable background execution | One application-scoped engine owned through a non-exported `dataSync` foreground service; sticky restart and durable queued-work recovery passed M7 |
| Process-death result recovery | Provider responses and MediaStore receipts are journaled so local saving can resume without another provider request or charge |
| Secret and endpoint handling | Logs, notifications, default `toString`, exported metadata, and persisted task/result-journal records exclude API keys and private endpoint details |
| Android backup | Secrets, private references, task data, and result journals are excluded from cloud backup and device transfer |

## Intentional Platform Replacements

| Desktop interaction | Android behavior |
| --- | --- |
| Output directory and Explorer reveal/open | MediaStore collection plus Gallery preview and Android share intents |
| Filesystem image chooser | Android Photo Picker followed by an app-owned durable copy |
| Completion sound | Configurable Android completion notification with audible or silent channel |
| Desktop table, right-click menus, and keyboard shortcuts | Generate, Tasks, Gallery, and Settings phone navigation with visible actions |
| Window size and desktop themes | Responsive single-column Compose UI, system light/dark theme, rotation, and accessibility behavior |

## Remaining Parity Work

These items are not currently supported and must not be described as implemented
until their corresponding work is merged:

- desktop-compatible PNG `parameters`/`tEXt` metadata read/write and golden-file
  interoperability;
- loading generation parameters directly from a desktop-generated PNG;
- desktop configuration import/export;
- user-selected output folders and Explorer-style file operations;
- clipboard image paste and drag-and-drop import;
- configurable concurrency above the mobile cap of two;
- an automatic bounded retention policy for terminal task history;
- additional visual themes and tablet-specific layouts.

Desktop-only interaction details such as right-click menus, window sizing, and
keyboard shortcuts are not parity requirements unless a separate
platform-appropriate Android workflow is approved.

## Known Current Limits

- A process death after MediaStore publishes an image but before its receipt is
  durable can create one duplicate local Gallery image. It does not repeat the
  provider request or charge and does not lose the generated bytes.
- A changed concurrency setting takes effect after the active foreground runtime
  becomes idle and restarts.
- Prompts and sanitized reference display names remain plaintext in the
  app-private Room database. API keys remain encrypted and never enter task or
  result-journal persistence.
- API 26-29 cannot discover a carrier-specific NAT64 prefix and recognize only
  the well-known `64:ff9b::/96` prefix.

## Release Status

M7 passed 114 offline Android tests, independent review, and the blocking API
26/29/33/36 CI device matrix. The hosted API 37 16 KB image remains a
non-blocking boot-timeout signal. No Android tag or signed public APK may be
published until a physical 16 KB device or Firebase Test Lab supplies a
successful API 37 instrumentation run and the signed candidate passes the
acceptance checklist in
[`../docs/ANDROID-SIDELOAD-RELEASE.md`](../docs/ANDROID-SIDELOAD-RELEASE.md).
