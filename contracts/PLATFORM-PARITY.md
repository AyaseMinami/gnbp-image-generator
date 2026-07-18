# Platform Parity Matrix

Status values describe the Android implementation after the M4 gate release and
the first M5 generation-workflow slice.

| Behavior | Desktop reference | Shared contract | Android status |
| --- | --- | --- | --- |
| Custom provider URL, key, and model | `config/config_mgr.py` | M0 decisions | Implemented M3 persistence; Generate consumes persisted profiles; editor in M6 |
| Gemini request and inline image response | `core/api_client.py` | `gemini/*.json` | Implemented M2; slice review passed |
| Gemini blocked/error response | `core/api_client.py` | `gemini/generate-blocked.json` | Implemented M2; slice review passed |
| Gemini `BLOCK_NONE` behavior | `core/api_client.py` | Resolved M0 decision 10 | Implemented M2; slice review passed |
| GPT JSON generation | `core/gpt_client.py` | `openai/generation-success.json` | Implemented M2; slice review passed |
| GPT multipart edit with references | `core/gpt_client.py` | Same response contract as generation | Implemented M2; slice review passed |
| GPT provider error response | `core/gpt_client.py` | `openai/error.json` | Implemented M2; slice review passed |
| Immutable batch task snapshot | `ui/main_window.py`, `core/task_queue.py` | Development plan Section 7 | Implemented M5 engine slice; independent review pending |
| Queue state and `OutcomeUnknown` | `core/task_queue.py` plus Android extension | Development plan Section 6 | Implemented M5 engine + Room task state; device UI test pending |
| Reference resize to 1536/JPEG 85 | `core/utils.py` | Development plan Section 7 | Implemented M4; gate passed |
| Collision-safe result identity | `core/utils.py` | Development plan Section 7 | Implemented M4; gate passed |
| Key/path metadata sanitization | `core/utils.py` | Development plan Section 7 | Implemented M4 allowlist; PNG `tEXt` parity later |
| Chinese fallback and English resources | Desktop UI plus resolved M0 decision 9 | Android resources | Implemented M1 |
| Application ID, SDK levels, and version source | Android-only | Resolved M0 decisions 1-2 | Implemented M1 |
| Strict TLS plus per-host compatibility mode | Desktop uses `verify=False`; Android decision differs | `docs/ANDROID-RELAY-TRANSPORT-SECURITY.md` | Implemented M2; slice review passed |
