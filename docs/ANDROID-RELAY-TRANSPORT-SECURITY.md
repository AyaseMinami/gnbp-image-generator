# Android Relay Transport Security Specification

Status: Proposed for M2 architecture review
Date: 2026-07-18

## 1. Scope

This specification defines the network-security behavior used by the Android
Gemini and OpenAI-compatible provider adapters. It is a prerequisite for M2
implementation because the application accepts user-configured relay endpoints,
including private relays that may use a private CA, a self-signed certificate,
an invalid hostname, or cleartext HTTP.

The specification covers:

- endpoint identity and per-profile isolation;
- TLS trust, certificate pinning, unsafe TLS, and cleartext modes;
- Android 17 local-network permission, certificate-transparency, ECH, and
  localhost behavior;
- redirects, retries, cancellation uncertainty, and error classification;
- logging and test requirements.

It does not define profile persistence UI, API-key encryption, provider JSON
payloads, or reliable background execution. Those are implemented in later
milestones against the types and invariants established here.

No production provider adapter may be implemented until this specification is
independently reviewed and its material findings are dispositioned.

## 2. Security Objectives

1. A new profile uses normal HTTPS certificate and hostname verification.
2. Compatibility is enabled explicitly for one profile and one exact endpoint
   authority. It never weakens another profile or host.
3. A safer compatibility mode is preferred whenever it can represent the relay:
   custom CA or pinned certificate before trust-all, and authenticated TLS before
   cleartext HTTP.
4. The application never follows a redirect or automatically retries a paid
   generation request.
5. A failure after request transmission may have started is surfaced as an
   uncertain outcome and is never converted into an automatic retry.
6. API keys, authorization headers, full request URLs, endpoint hosts, prompts,
   image bodies, certificates, and content URIs are absent from diagnostic logs.

## 3. Verified Android 17 Platform Behavior

The project targets Android 17 / API level 37. The following platform behavior
is therefore load-bearing rather than future hardening:

- Apps targeting API 37 must obtain the `ACCESS_LOCAL_NETWORK` runtime
  permission for direct traffic to and from local-network addresses. The rule is
  implemented below individual networking libraries and applies to OkHttp.
- Localhost and loopback are separate from LAN access. Android 17 supplies an
  implicit localhost network-security configuration when no localhost-specific
  configuration exists. That implicit configuration allows cleartext, disables
  certificate transparency and pinning for localhost, and delegates trust
  anchors to the base configuration. On a physical device, localhost means the
  Android device, not the developer computer.
- Certificate transparency is enabled by default for apps targeting API 37.
  Private relay certificates must not be assumed to satisfy public CT policy.
- ECH is enabled by default for apps targeting API 37 when the networking library
  and server support it. If ECH is unavailable, the library may send ECH GREASE.
  Android's `<domainEncryption>` control is static network-security XML, while
  GNBP profile hosts are dynamic user data.
- Cleartext traffic is denied by default for apps targeting API 28 and later.
  Android network-security XML can allow named static domains, but it cannot add
  arbitrary profile hosts at runtime.

Primary references:

- [Android 17 behavior changes for target API 37](https://developer.android.com/about/versions/17/behavior-changes-17)
- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
- [Network security configuration, including CT, ECH, and localhost](https://developer.android.com/privacy-and-security/security-config)
- [`usesCleartextTraffic`](https://developer.android.com/guide/topics/manifest/application-element#usesCleartextTraffic)

## 4. Deep Transport Module

Provider-specific adapters share one internal deep module:

```text
ProviderHttpTransport.execute(ProviderHttpCall) -> ProviderHttpResult
```

The interface accepts a structured call containing the final request URL,
method, headers (including sensitive values needed for transmission), body, and
a `TransportBinding`. Sensitive fields are typed so they cannot be sent to
logging or diagnostics. It returns a typed response or typed failure. It does
not expose OkHttp clients, trust managers, SSL contexts, interceptors, or Android
permission objects to provider adapters.

The implementation owns:

- endpoint and authority validation;
- selection and lifecycle of policy-scoped OkHttp clients;
- TLS trust and pin enforcement;
- LAN permission checks;
- redirect and retry prevention;
- cancellation and delivery-certainty tracking;
- response-size limits and redacted diagnostics.

The production adapter is `OkHttpProviderHttpTransport`. Tests may supply an
in-memory fake at the same seam when provider serialization is the behavior
under test. OkHttp behavior itself is tested offline with MockWebServer.

## 5. Endpoint Identity And Binding

Every persisted profile has a full base URL and exactly one transport binding:

```text
TransportBinding
  profileId
  EndpointAuthority(scheme, asciiHost, effectivePort)
  TransportSecurityMode
  LocalNetworkMode
  policyRevision
```

The endpoint parser must use a structured URL parser. It must:

- accept only `https` and `http`;
- reject user information, fragments, and an empty host;
- canonicalize the scheme, IDN host, IPv4/IPv6 literal, and default port;
- retain a base path separately from authority identity;
- never compare hosts using substring, suffix, wildcard, or raw input text.

Before DNS or socket work, the transport verifies that the final provider URL's
scheme, host, and effective port exactly match the binding. Every redirect is
rejected and returned as `RedirectRejected`; automatic redirect following is
disabled. This prevents authority changes, HTTPS downgrade, credential
forwarding, and implicit replay of a POST body.

Changing a profile's scheme, host, or port invalidates its client, unsafe-mode
acknowledgement, custom certificate binding, and pins. Changing only the base
path does not change the authority, but still produces a new immutable request
snapshot for future tasks.

## 6. Transport Security Modes

### 6.1 Verified TLS

`VerifiedTls` is the default and has two independent optional refinements:

- `CustomCa`: add one or more user-imported X.509 CA certificates for this
  binding;
- `SpkiPins`: require one or more exact SHA-256 public-key pins for this binding.

Without either refinement, verified TLS uses system trust. Verified TLS always
retains strict hostname verification, certificate validity checks, and normal
chain validation. Pinning is applied after trust validation; a pin alone does
not make an otherwise invalid certificate trusted.

Custom CA material must be DER or PEM X.509 data. PKCS#12 files, private keys,
and executable/provider-supplied downloads are rejected. The certificate is
selected by the user and is never fetched automatically from the untrusted
endpoint.

### 6.2 Pinned Server Certificate

`PinnedServerCertificateTls` supports a relay whose self-signed leaf certificate
cannot be represented as a CA. The user imports the complete certificate. The
transport accepts only the matching certificate/public key for the bound
authority and still validates certificate dates.

Normal hostname verification remains the default. A hostname-mismatch override
may be enabled only when the imported certificate pin is present, because the
explicit pin then supplies endpoint identity. This override is part of the same
binding and is invalidated when the authority or certificate changes.

This mode is preferred over trust-all for a stable self-signed relay.

### 6.3 Unsafe Trust-All TLS

`UnsafeTrustAllTls` disables certificate-chain and hostname verification only
inside a client dedicated to the exact profile and authority. It preserves TLS
encryption but provides no authenticated server identity.

Requirements:

- the mode cannot be the default or be enabled by migration;
- enabling it requires a non-preselected confirmation after a warning explains
  interception and API-key theft risk;
- the profile and generation UI retain a visible unsafe indicator;
- acknowledgement records the profile, authority, mode, policy revision, and
  time, and is invalidated when any binding field changes;
- no trust-all trust manager, hostname verifier, connection pool, or derived
  OkHttp client is shared with another binding.

### 6.4 Cleartext HTTP

`CleartextHttp` is an unsafe fallback with the same acknowledgement and visible
warning requirements as trust-all. The transport rejects HTTP before network I/O
unless the call and binding both select this mode. It never downgrades HTTPS to
HTTP.

Material platform tradeoff: arbitrary relay hosts are runtime data, but Android
cleartext allowlists are static. Supporting arbitrary HTTP profiles therefore
requires the application manifest/network-security policy to permit cleartext
globally, followed by exact per-binding enforcement in
`ProviderHttpTransport`. This loses the platform's second line of defense if
unrelated future code attempts HTTP. The first release accepts that cost only if
the architecture review confirms all provider traffic crosses this module and
tests prove strict bindings reject HTTP before DNS.

If that invariant cannot be maintained, generic cleartext relay support must be
removed rather than silently becoming global application behavior.

## 7. Client Isolation

OkHttp clients are cached only by a fingerprint containing profile ID, exact
authority, security mode, certificate/pin digest, and policy revision. A client
is discarded when the fingerprint changes.

- Never create a strict client with `unsafeClient.newBuilder()` or the reverse.
- Unsafe and custom-trust clients have dedicated connection pools.
- A transport call cannot supply or override its own trust manager.
- Certificate and hostname exceptions are exact-host, not wildcard rules.
- Provider adapters cannot access a generic unbound HTTP client.
- Internal HTTP logging interceptors are disabled in release and tests verify
  that exception messages are sanitized before reaching application logs.

## 8. Local-Network And Loopback Behavior

`LocalNetworkMode` has two values:

- `InternetOrLoopbackOnly` (default);
- `AllowLan` (explicit per profile).

For an IP literal or a DNS result that is private, link-local, site-local,
multicast, or broadcast, the transport requires `AllowLan`. Loopback addresses
are excluded from that LAN classification. A DNS wrapper validates every
resolved address before returning it to OkHttp, so a hostname resolving to a LAN
address cannot bypass the profile choice.

On API 37 and later, `AllowLan` also requires the runtime
`ACCESS_LOCAL_NETWORK` grant. The app declares the permission in M2, but the UI
requests it only when a user enables or runs a LAN profile. Denial returns a
typed `LocalNetworkPermissionRequired` failure and must not be reported as a
generic timeout. API 26-36 still require the explicit profile choice even though
the platform has no equivalent runtime permission.

System-mediated device pickers are not applicable because a GNBP relay is a
user-entered HTTP endpoint, not a discovered casting or IoT device.

Loopback remains subject to the selected GNBP transport mode. Android 17's
implicit localhost cleartext allowance must not cause an `http://localhost`
profile to bypass GNBP's explicit `CleartextHttp` acknowledgement.

## 9. Android 17 CT And ECH Policy

Strict verified TLS retains Android's default certificate-transparency and ECH
behavior. The application does not disable either feature globally.

Private CA and pinned-certificate modes may need a policy-scoped trust
implementation because dynamic profile hosts cannot be listed in static Android
network-security XML. The implementation must demonstrate on API 37 that the
approved private-certificate cases work without disabling CT for unrelated
hosts. It must continue to enforce the certificate, expiry, hostname, and pin
rules defined above.

The first release does not expose an ECH switch. Android's per-domain ECH
configuration is static, while profile domains are runtime data, and disabling
ECH globally would reduce privacy for every strict profile. If an approved relay
fails specifically because of ECH or ECH GREASE, that is a new compatibility
case requiring evidence and a separate disposition; the adapter must not
silently downgrade ECH behavior.

API 37 validation before M5 must include at least one strict public TLS endpoint
and each approved private relay certificate mode. Real endpoint checks are
manual opt-in tests and must never enter CI, source control, or logs.

## 10. Retries, Redirects, Cancellation, And Certainty

All production clients set automatic connection retry and redirect behavior off:

```text
retryOnConnectionFailure = false
followRedirects = false
followSslRedirects = false
```

The application does not retry HTTP 429, HTTP 5xx, timeouts, broken connections,
or TLS failures automatically. Explicit user retry creates a new task only in a
later workflow milestone.

The transport tracks request progress and attaches `DeliveryCertainty` to every
failure:

- `NotSent`: validation, permission, DNS, connection, or TLS failed before
  request headers/body transmission began;
- `PossiblySent`: transmission began but no complete provider response was
  received;
- `Responded`: an HTTP response was received, even if it was an error response.

Cancellation before transmission is `Cancelled(NotSent)`. Cancellation,
timeout, or connection loss after transmission starts is
`RequestOutcomeUnknown(PossiblySent)`. M5 maps the latter to task state
`OutcomeUnknown` and never retries it automatically.

## 11. Typed Failures

The transport result distinguishes at least:

- invalid endpoint or binding mismatch;
- unsafe acknowledgement missing or stale;
- LAN mode disabled or runtime permission required;
- DNS, connection, and timeout failures;
- certificate-chain, hostname, pin, CT, and generic TLS-handshake failures when
  the platform exposes a reliable distinction;
- cleartext rejected;
- redirect rejected;
- cancellation and uncertain request outcome;
- HTTP status response with a redacted, size-limited provider message;
- malformed or oversized response.

Provider adapters may translate these into provider-domain errors, but they must
preserve `DeliveryCertainty`. Raw exceptions must not cross into UI or logs.

## 12. Logging And Secret Handling

- Do not install OkHttp body/header logging interceptors.
- Gemini's compatibility query parameter and OpenAI's authorization header are
  never included in application logs or exception text.
- Logs use provider type, task ID, typed error code, and coarse timing only.
- Logs do not include profile names, endpoint scheme/host/port/path, prompts,
  provider response bodies, reference-image names, certificate subjects, or
  pins.
- User-facing diagnostics may identify the configured profile and display a
  sanitized error inside the app, but those strings are not written to logs.
- Unit tests use sentinel secrets and fail if any captured log contains them or
  their encoded URL form.

## 13. Offline Test Matrix

M2 tests use MockWebServer and generated test certificates. They never call a
real provider.

Required cases:

1. Strict TLS accepts the test trust root and rejects self-signed, expired,
   wrong-host, and wrong-pin certificates.
2. Custom CA succeeds only for the bound profile/authority; a second profile and
   second host remain strict.
3. Pinned server certificate accepts only the selected certificate and exercises
   both strict-hostname and explicit pinned-hostname-override behavior.
4. Trust-all succeeds only after valid acknowledgement and cannot leak through a
   shared client, pool, redirect, or client cache.
5. Cleartext succeeds only for an acknowledged cleartext binding; strict HTTP is
   rejected before DNS or socket use.
6. Every 3xx response is returned as `RedirectRejected` without a second request.
7. A dropped connection before transmission is `NotSent`; a dropped connection,
   cancellation, or timeout after transmission begins is `PossiblySent`.
8. Private, link-local, multicast, and loopback destinations exercise the local
   network classification rules.
9. API key sentinels in Gemini URLs, OpenAI headers, exception messages, and
   provider error bodies never appear in captured logs.
10. Malformed JSON, oversized responses, HTTP errors, cancellation, and timeout
    remain typed and retain delivery certainty.

API 37 instrumentation/manual checks additionally verify runtime LAN denial,
grant, and revocation; localhost behavior; strict public TLS; and every approved
private-certificate mode. These checks remain offline where a local test server
can represent the case. Any real relay smoke test is manually enabled and
redacted.

## 14. Architecture Gate Decisions

The reviewer must explicitly accept or reject these material choices before M2
adapter coding begins:

1. One deep `ProviderHttpTransport` module owns transport policy for both
   provider adapters.
2. All redirects and automatic connection retries are disabled.
3. Custom CA and pinned-server-certificate modes are implemented before the
   trust-all fallback.
4. Unsafe modes require binding-specific acknowledgement and permanent visible
   UI state.
5. Generic HTTP relay support uses global Android cleartext permission plus
   exact application-layer binding enforcement, because runtime host allowlists
   cannot be expressed in static network-security XML.
6. LAN access is a separate per-profile choice and runtime permission; localhost
   is not treated as LAN.
7. CT and ECH remain globally enabled. Private certificate compatibility is
   scoped in the transport implementation, and ECH has no first-release toggle.
8. Every failure preserves whether a paid request was possibly sent.
