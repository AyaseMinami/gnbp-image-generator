# ADR 0001: Native Android Implementation

Status: Accepted
Date: 2026-07-17

## Context

GNBP Image Generator currently has a Python 3.11 and PySide6 Windows
implementation. The application contains about 2,200 lines of production Python,
of which roughly 70 percent is Qt desktop UI and theme code. The provider logic
is comparatively small: it builds Gemini and OpenAI-compatible HTTP requests,
prepares reference images, parses base64 results, saves PNG files, and coordinates
an in-memory task queue.

The Android edition must provide a mobile-native workflow, Android content URI
and MediaStore integration, protected credential storage, long-request handling,
notifications, and lifecycle-aware task state. The project does not require
source reuse. It does require observable behavior and provider compatibility to
remain aligned with the desktop edition.

This is a high-consequence decision because it chooses whether the project will
maintain two platform implementations or introduce a shared runtime/framework.

## Decision

Implement the Android edition in Kotlin with Jetpack Compose as a self-contained
Gradle project under `android/`.

Share specifications, redacted provider fixtures, metadata fixtures, and parity
tests between platforms. Do not share executable source code initially. Place
the principal Android behavior behind a small `GenerationEngine` interface, with
provider, asset-store, and profile-store adapters at real seams.

The user accepted this decision during M0 after independent review and confirmed
that no iOS edition is planned in the next 12 months.

## Rationale

- Most existing source that would be reusable is platform-independent HTTP and
  data transformation code small enough to reimplement and contract-test.
- The existing Qt Widgets UI is desktop-specific and must be redesigned for a
  phone regardless of runtime choice.
- Native Android has direct, maintained interfaces for Photo Picker, content
  URIs, MediaStore, Keystore, notifications, foreground work, accessibility, and
  lifecycle testing.
- Kotlin coroutines and typed task state fit long-running requests and
  cancellation better than preserving the current Qt Signal and Python thread
  implementation.
- The application currently targets Android only. Paying a cross-platform
  abstraction cost for a hypothetical iOS edition would create a seam with only
  one production adapter.
- Behavior drift is better controlled by shared contract fixtures and parity
  tests than by embedding a second runtime solely to reuse a few hundred lines.

## Alternatives Considered

### Reuse Python With PySide6, Kivy, BeeWare, Or Chaquopy

This can reuse some provider and image-processing implementation. It does not
reuse the desktop UI, filesystem assumptions, Qt task signaling, Explorer
actions, or Windows packaging. Python and binary dependency packaging adds APK
size and build complexity, while Android lifecycle, content URI, notification,
and background-work integration still require platform adapters. Chaquopy could
embed a narrow Python core, but the core is not currently deep enough to justify
maintaining a second runtime and a Kotlin/Python interface.

Rejected for the initial implementation. Reconsider only if the shared Python
domain implementation grows substantially and can present a stable, high-
leverage interface.

### Flutter

Flutter is a credible production option and gives a future iOS route. It would
still require a full UI and provider rewrite in Dart plus Android adapters for
long-running work, secure secrets, MediaStore, and unusual relay TLS behavior.
There is no current Dart implementation or committed iOS requirement, so its
cross-platform leverage is hypothetical.

Rejected while Android is the only committed mobile platform. Reconsider if an
iOS edition becomes an approved near-term product requirement.

### Kotlin Multiplatform

Kotlin Multiplatform could share future provider and domain code with another
Kotlin-based client, but it cannot share executable code with the existing
Python desktop application. Introducing multiplatform source sets before a
second Kotlin target exists would add build and interface complexity without a
current second adapter.

Rejected for the initial Android implementation. Internal Android modules should
remain separable enough to extract later if a real second Kotlin target appears.

### Progressive Web Application

A PWA would simplify distribution and could support common UI and request logic.
It is a poor fit for durable background work, large base64 image memory use,
native result storage, protected BYOK credentials, and relay endpoints with
invalid TLS certificates. Browsers do not provide an application-controlled
trust-all certificate mode, which may exclude an important existing relay use
case.

Rejected because platform constraints affect core product behavior rather than
only presentation.

## Consequences

Positive consequences:

- first-class Android UX, platform integration, lifecycle behavior, testing, and
  release tooling;
- no Python runtime or cross-framework packaging in the APK;
- Android implementation can use typed models and secure defaults instead of
  carrying forward desktop implementation constraints;
- each platform can evolve internally while shared contracts protect important
  observable behavior.

Negative consequences:

- provider, configuration, queue, and media behavior exist in two languages;
- protocol changes may require coordinated edits in both implementations;
- shared fixtures and parity tracking become required project maintenance;
- a later iOS commitment may cause Flutter or Kotlin Multiplatform to be
  reconsidered after native Android work already exists.

## Validation And Reconsideration Triggers

Validate this decision during M0 by confirming:

- Android is the only committed mobile target for the first release;
- relay compatibility can be implemented within acceptable Android security and
  distribution constraints;
- contract fixtures cover the provider behavior that would otherwise motivate
  source reuse;
- the team accepts independent platform releases and implementations.

Reconsider this ADR if:

- an iOS edition becomes a funded near-term requirement;
- shared non-UI behavior grows enough that duplication dominates platform code;
- required relay behavior cannot be supported by the native Android networking
  and distribution model;
- Android-specific integration becomes less important than universal web access.
