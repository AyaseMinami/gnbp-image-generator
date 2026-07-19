# Cross-Platform Contracts

This directory contains redacted, offline behavior fixtures shared by the
desktop and Android implementations. Fixtures describe provider response shapes
and product invariants; they never contain real API keys, private endpoints,
prompts, reference images, or generated user content.

The JSON files are minimal contract examples derived from the response shapes
accepted by the existing desktop clients. Android provider tests have consumed
them through MockWebServer since M2. Future desktop contract tests should consume
the same files rather than copying payloads into Python test modules.

[`PLATFORM-PARITY.md`](PLATFORM-PARITY.md) records the implemented M7 behavior,
intentional Android adaptations, remaining desktop-parity work, and public
release gates. It is a living status document rather than an implementation
roadmap.

`PLACEHOLDER_BASE64_PNG` is deliberately not real image data. Tests that need to
decode an image must replace it in-memory with a deterministic test PNG rather
than committing generated or private image output.
