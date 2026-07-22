# ADR 0002: Shared Product Domain Context

Status: Accepted
Date: 2026-07-22

## Context

GNBP has independent Windows and Android implementations. ADR 0001 deliberately
keeps their executable source separate while requiring shared observable
behavior and parity tracking. Engineering workflows now publish cross-platform
issues and need one vocabulary for product concepts even when platform APIs and
internal types differ.

Separate platform glossaries would allow terms such as task, generated result,
reference image, provider profile, and task state to acquire incompatible
meanings. Treating implementation boundaries as domain boundaries would weaken
the shared contracts that currently control behavior drift.

## Decision

Use one product domain context for the repository. Record shared terminology in
a root `CONTEXT.md` when domain-modeling work requires it, keep system-wide
architecture decisions under `docs/adr/`, and use
`contracts/PLATFORM-PARITY.md` to document intentional platform differences.

The canonical concepts for current planning are generation task, generated
result, reference image, provider profile, and task state. A generated result
owns or references a generated image; those terms are related but not
interchangeable. Platform-specific implementation names may differ without
changing these product meanings.

## Consequences

- Issues and acceptance criteria use the same product vocabulary across both
  implementations.
- Platform-specific storage, lifecycle, and UI mechanisms remain independent.
- New terminology decisions belong in the shared context rather than a private
  platform glossary.
- A future genuinely independent product area may justify revisiting this ADR
  and introducing a context map.
