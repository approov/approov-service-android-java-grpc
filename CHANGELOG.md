# Changelog

All notable changes to this package will be documented in this file.

The format is based on Keep a Changelog and this project adheres to Semantic Versioning.

## [3.5.4] - 2026-06-14

### Added
- Added a customizable service mutator system (`ApproovServiceMutator`, default `ApproovServiceMutator.DEFAULT`), installable via `setServiceMutator` / `getServiceMutator`, allowing the default fail-closed token-fetch and attestation-result handling to be overridden.
- Added HTTP message signing (RFC 9421) via `ApproovDefaultMessageSigning` (an `ApproovServiceMutator`). It adds `Signature` / `Signature-Input` metadata once an Approov token is present. The default factory signs `@method`, `@target-uri`, the Approov token and trace-ID headers, and the `Authorization` header when present, using install (ES256) signing by default; account (HMAC-SHA256) signing is also supported. No body digest is produced for gRPC (there is no accessible request body at the interceptor layer). The ES256 ASN.1/DER signature is decoded to raw `r||s` form by a self-contained byte parser, adding no new dependency.
- Message signing is **fail-open**: signing failures (no signature available, base64 decode failure, ASN.1/DER decode failure) proceed unsigned and are logged at error level. Only an unsupported signing algorithm fails closed (throws `ApproovException`).
- Added `getInstallMessageSignature` and `getAccountMessageSignature`.
- Added `setApproovTraceIDHeader` (default `Approov-TraceID`) to configure or disable the optional trace ID header, which is now emitted on protected requests.
- Added `setUseApproovStatusIfNoToken` to inject the fetch status string into the token header when no real token is available.
- Added `addExclusionURLRegex` / `removeExclusionURLRegex` to exclude matching requests from Approov mutation (pinning may still apply).
- The `ApproovClientInterceptor` now threads the RPC path (`"/" + method.getFullMethodName()`) through to the service layer for message signing (`@path` / `@target-uri`) and exclusion URL matching.
- Added `README.md`, `USAGE.md`, and `REFERENCE.md` documentation files for the service layer.
- Added `SECURITY.md` file.

### Changed
- Refactored `ApproovService.addApproov` to route through the active `ApproovServiceMutator` (exclusion check → token fetch → token/trace headers → header substitution → processed-request hook). A new `addApproov(String host, String path, Metadata headers)` overload threads the RPC path; the previous `addApproov(String host, Metadata headers)` is deprecated and delegates to it.
- On every **successful** initialization (including same-config re-initialization and bypass→protected upgrades), the service-layer state — including the custom service mutator — is reset to its defaults (Service Mutator Reset).

### Deprecated
- `setProceedOnNetworkFail` is now a no-op. Use `setServiceMutator` to customize behavior on a networking failure.
- `getMessageSignature` is deprecated in favor of `getAccountMessageSignature` / `getInstallMessageSignature`; it now delegates to `getAccountMessageSignature`.

### Fixed
- `setApproovHeader(header, prefix)` now treats a `null` prefix as no prefix (empty string). Previously a `null` prefix was concatenated directly with the token, producing a malformed `"null<token>"` header value; the behaviour now matches `addSubstitutionHeader`.
- Request processing iterates a synchronized snapshot of the substitution-header map instead of the live map, removing a `ConcurrentModificationException` window when `initialize()` resets state on another thread while a request is in flight.
- The trace-ID header is now emitted with an empty value (rather than omitted) when the SDK returns an empty trace ID on a protected request, providing backend evidence that Approov processing occurred (matches the token-header behaviour).
- The published AAR now bakes the release version into `BuildConfig.APPROOV_SERVICE_VERSION` (CI passes `-PapproovServiceVersion=<tag>`); previously the Maven-published artifact reported the local-dev default `"dev"`.

### Security
- TLS pinning behaviour clarified. `ApproovPinningHostnameVerifier` reads the current live Approov pins via `ApproovService.getPins`; the obsolete `ApproovService.prefetch()` call (a no-op) was removed from the verify path. The standard OS hostname/certificate verification still runs first, and bypass mode (empty-config initialization) still applies OS certificate validation. Pins are enforced at TLS handshake time, so a tightened pin set takes effect on the next (re)connection; an already-established long-lived gRPC/HTTP-2 channel is not forcibly torn down — callers needing immediate enforcement on live connections should rebuild the channel after a configuration update.

## [3.5.3] - 2026-01-15

### Added
- Added `getLastARC` method to expose the last Attestation Response Code.
- Exposed application install attributes to the service layer.
- Updated underlying Approov Android SDK to version 3.5.3.

## [3.5.1] - 2025-10-17

### Changed
- Updated underlying Approov Android SDK dependency to version 3.5.1.

## [3.5.0] - 2025-08-01

### Changed
- Updated underlying Approov Android SDK dependency to version 3.5.0.
