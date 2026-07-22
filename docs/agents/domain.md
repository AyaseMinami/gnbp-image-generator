# Domain Docs

GNBP is a single product context with separate Windows and Android
implementations.

Before domain work, read the root `CONTEXT.md` when it exists, relevant ADRs
under `docs/adr/`, and `contracts/PLATFORM-PARITY.md` for intentional platform
differences. If `CONTEXT.md` does not exist, proceed without creating it
automatically; domain-modeling work may create it when terminology decisions
are needed.

Use shared product terms consistently across platforms. Platform implementation
differences do not create separate meanings for generation task, generated
result, reference image, provider profile, or task state. A generated result's
media is the generated image.

Surface any conflict with an accepted ADR rather than silently overriding it.
