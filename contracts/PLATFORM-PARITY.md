# Platform Parity Matrix

Status values describe the Android implementation after the accepted M1
scaffold review and before the M2 architecture gate.

| Behavior | Desktop reference | Shared contract | Android status |
| --- | --- | --- | --- |
| Custom provider URL, key, and model | `config/config_mgr.py` | M0 decisions | Planned M3 |
| Gemini request and inline image response | `core/api_client.py` | `gemini/*.json` | Planned M2 |
| Gemini blocked/error response | `core/api_client.py` | `gemini/generate-blocked.json` | Planned M2 |
| Gemini `BLOCK_NONE` behavior | `core/api_client.py` | Resolved M0 decision 10 | Planned M2 |
| GPT JSON generation | `core/gpt_client.py` | `openai/generation-success.json` | Planned M2 |
| GPT multipart edit with references | `core/gpt_client.py` | Same response contract as generation | Planned M2 |
| GPT provider error response | `core/gpt_client.py` | `openai/error.json` | Planned M2 |
| Immutable batch task snapshot | `ui/main_window.py`, `core/task_queue.py` | Development plan Section 7 | Planned M5 |
| Queue state and `OutcomeUnknown` | `core/task_queue.py` plus Android extension | Development plan Section 6 | Planned M5 |
| Reference resize to 1536/JPEG 85 | `core/utils.py` | Development plan Section 7 | Planned M4 |
| Collision-safe result identity | `core/utils.py` | Development plan Section 7 | Planned M4 |
| Key/path metadata sanitization | `core/utils.py` | Development plan Section 7 | Planned M4; PNG parity later |
| Chinese fallback and English resources | Desktop UI plus resolved M0 decision 9 | Android resources | Implemented M1 |
| Application ID, SDK levels, and version source | Android-only | Resolved M0 decisions 1-2 | Implemented M1 |
| Strict TLS plus per-host compatibility mode | Desktop uses `verify=False`; Android decision differs | `docs/ANDROID-RELAY-TRANSPORT-SECURITY.md` | Accepted architecture; Planned M2 |
