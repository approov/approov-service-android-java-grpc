# Reference

This document provides a reference for the public methods and classes exposed by the Approov Service for Android Java Clients using gRPC. These are available in the package:

```java
import io.approov.service.grpc.ApproovService;
import io.approov.service.grpc.ApproovChannelBuilder;
import io.approov.service.grpc.ApproovClientInterceptor;
import io.approov.service.grpc.ApproovException;
import io.approov.service.grpc.ApproovRejectionException;
import io.approov.service.grpc.ApproovNetworkException;
```

Most `ApproovService` methods either throw an `ApproovException` or return the expected result. The specialized exception classes are:

- `ApproovNetworkException`: Raised for temporary networking issues. Offers the user a retry path.
- `ApproovRejectionException`: Raised when the device attestation fails. Includes ARC (Attestation Response Code) and rejection reasons.

---

## ApproovService

### initialize(Context context, String config)

Initializes the Approov SDK with the configuration string. This should be called **once** during app startup, typically in the `onCreate` method of your custom `Application` class.

```java
ApproovService.initialize(context, "<config-string>");
```

If an attempt is made to initialize with a different non-empty config, an error log is emitted and the call is ignored. Passing an empty config string or null bypasses Approov SDK initialization, running in bypass mode.

### setProceedOnNetworkFail(boolean proceed) — *deprecated*

> **Deprecated.** This method is now a no-op. Use [`setServiceMutator`](#setservicemutatorapproovservicemutator-mutator) to customize the behavior on a networking failure.

```java
ApproovService.setProceedOnNetworkFail(true); // no-op
```

### setBindingHeader(String header)

Sets a binding header that must be present on requests using the Approov service. A hash of the header value is included in the issued Approov tokens.

```java
ApproovService.setBindingHeader("authorization");
```

### setApproovHeader(String header, String prefix)

Sets the header that the Approov token is added on, as well as an optional prefix String (such as `"Bearer "`). By default, the token is provided on `"Approov-Token"` with no prefix.

```java
ApproovService.setApproovHeader("Approov-Token", "");
```

### setDevKey(String devKey)

Sets a development key indicating that the app is a development version.

```java
ApproovService.setDevKey("<dev-key>");
```

### prefetch()

Permits a token to be prefetched as early as possible to hide initial fetch latency.

```java
ApproovService.prefetch();
```

### addSubstitutionHeader(String header, String requiredPrefix)

Adds a header name to be subject to secure string substitution.

```java
ApproovService.addSubstitutionHeader("Api-Key", null);
```

### removeSubstitutionHeader(String header)

Removes a header previously added for substitution.

```java
ApproovService.removeSubstitutionHeader("Api-Key");
```

### addExclusionURLRegex(String urlRegex)

Adds an exclusion URL regular expression. Requests whose reconstructed URL matches the regex are forwarded by the interceptor **without** any Approov request mutation (no token, trace ID, message signing, or secure string substitution). Certificate pinning may still be applied to the host.

For gRPC there is no natural request URL at the interceptor layer, so the regex is matched against a reconstructed URL: `"https://<hostname><path>"` when an RPC path is available (the RPC path is of the form `/package.Service/Method`), otherwise the bare hostname.

```java
ApproovService.addExclusionURLRegex("https://grpc.example.com/health\\..*");
```

### removeExclusionURLRegex(String urlRegex)

Removes an exclusion URL regex previously added with `addExclusionURLRegex`. Subsequent matching requests immediately revert to standard processing.

```java
ApproovService.removeExclusionURLRegex("https://grpc.example.com/health\\..*");
```

### setApproovTraceIDHeader(String header)

Sets the header on which the optional Approov trace ID is placed. The default is `"Approov-TraceID"`. Pass `null` to disable emission of the trace ID header.

```java
ApproovService.setApproovTraceIDHeader("Approov-TraceID");
```

### setUseApproovStatusIfNoToken(boolean shouldUse)

When enabled, the Approov fetch status string is injected into the token header when a real Approov token cannot be obtained, giving the backend visibility into the reason a token was unavailable.

```java
ApproovService.setUseApproovStatusIfNoToken(true);
```

### setServiceMutator(ApproovServiceMutator mutator)

Installs a custom [`ApproovServiceMutator`](#approovservicemutator) that can override the default fail-closed interceptor behavior and the default attestation-result handling. Pass `null` to restore the default mutator.

> The custom mutator is reset to `ApproovServiceMutator.DEFAULT` on every **successful** initialization (including same-config re-initialization and upgrades from bypass to protected mode), so custom overrides do not unexpectedly persist across initialization boundaries.

```java
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning()
    .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));
```

### getServiceMutator()

Returns the currently active service mutator (never `null`).

### getDeviceID()

Gets the device ID used by Approov.

```java
String deviceId = ApproovService.getDeviceID();
```

### setDataHashInToken(String data)

Directly sets the data hash for subsequently fetched Approov tokens. This is the manual alternative to `setBindingHeader`.

```java
ApproovService.setDataHashInToken("<data-to-hash>");
```

### fetchToken(String url)

Performs a token fetch for the given URL. Throws `ApproovException` if the fetch fails.

```java
String token = ApproovService.fetchToken("https://example.com/api");
```

### getMessageSignature(String message) — *deprecated*

> **Deprecated.** Use [`getAccountMessageSignature`](#getaccountmessagesignaturestring-message) or [`getInstallMessageSignature`](#getinstallmessagesignaturestring-message) instead.

Gets the signature for the given message using the account-specific message signing key. Delegates to `getAccountMessageSignature` and throws `ApproovException` if no signature is available.

```java
String signature = ApproovService.getMessageSignature("message");
```

### getAccountMessageSignature(String message)

Gets the account message signature for the given message, using an account-specific signing key transmitted to the SDK after a successful fetch (if the facility is enabled for the account). Returns `null` if no signature is available (no prior fetch, the feature is not enabled, or the service layer is in bypass mode). An Approov token should always be included in the signed message to prevent replay.

```java
String signature = ApproovService.getAccountMessageSignature("message");
```

### getInstallMessageSignature(String message)

Gets the install message signature for the given message, using an install-specific signing key. Returns `null` if no signature is available (for example key pair generation is not supported on the device) or if the service layer is in bypass mode. An Approov token containing the public key should be included in the signed message.

```java
String signature = ApproovService.getInstallMessageSignature("message");
```

### fetchSecureString(String key, String newDef)

Fetches a secure string with the given key.

```java
String value = ApproovService.fetchSecureString("api_key", null);
```

### fetchCustomJWT(String payload)

Fetches a custom JWT with the given payload.

```java
String jwt = ApproovService.fetchCustomJWT("{\"claims\":{}}");
```

### precheck()

Performs a precheck to verify if the app will pass attestation. Throws `ApproovException` (e.g. `ApproovRejectionException` if the app fails attestation).

```java
ApproovService.precheck();
```

### getLastARC()

Gets the last Attestation Response Code (ARC) code.

```java
String arc = ApproovService.getLastARC();
```

### setInstallAttrsInToken(String attrs)

Sets installation-specific attributes in the token.

```java
ApproovService.setInstallAttrsInToken("my-attributes");
```

---

## ApproovChannelBuilder

`ApproovChannelBuilder` provides factory functions that return a `ManagedChannel` pre-configured to use the `ApproovPinningHostnameVerifier` for certificate trust evaluation.

### forAddress(String host, int port)

```java
ManagedChannel channel = ApproovChannelBuilder.forAddress("grpc.example.com", 443).build();
```

### forTarget(String target)

```java
ManagedChannel channel = ApproovChannelBuilder.forTarget("grpc.example.com:443").build();
```

### forTarget(String target, ChannelCredentials creds)

```java
ManagedChannel channel = ApproovChannelBuilder.forTarget("grpc.example.com:443", creds).build();
```

---

## ApproovClientInterceptor

An interceptor that automatically mutates request metadata to inject Approov tokens and perform secure string substitutions. It captures the RPC path (`method.getFullMethodName()`) and threads it (as `"/" + fullMethodName`) through to the service layer, so message signing can include the `@path` / `@target-uri` derived components and exclusion URL matching can use the full path.

```java
ApproovClientInterceptor interceptor = new ApproovClientInterceptor(channel);
```

---

## ApproovServiceMutator

`ApproovServiceMutator` is an interface that lets you override the service layer's default fail-closed decision making at key points in the attestation and interceptor flows. Every method has a default implementation, so you only override the hooks you care about. The default mutator is exposed as `ApproovServiceMutator.DEFAULT`.

The default behavior is **fail-closed** for all token fetch statuses except `SUCCESS` and the unprotected statuses `NO_APPROOV_SERVICE` / `UNKNOWN_URL` / `UNPROTECTED_URL` (which are forwarded without a token). See the [fetch status handling documentation](https://ext.approov.io/docs/latest/approov-direct-sdk-integration/#fetch-status-handling).

Because gRPC carries no URL query string or accessible request body at the interceptor layer, the gRPC mutator omits query-parameter substitution and operates on the request hostname, the optional RPC path, and the gRPC `io.grpc.Metadata` headers.

```java
public interface ApproovServiceMutator {
    ApproovServiceMutator DEFAULT = ...;

    final class ApproovRequest {                 // (hostname, path, Metadata headers)
        public ApproovRequest(String hostname, String path, Metadata headers);
        public String getHostname();
        public String getPath();                 // "/package.Service/Method" or null
        public Metadata getHeaders();
    }

    void handlePrecheckResult(Approov.TokenFetchResult r) throws ApproovException;
    void handleFetchTokenResult(Approov.TokenFetchResult r) throws ApproovException;
    void handleFetchSecureStringResult(Approov.TokenFetchResult r, String op, String key) throws ApproovException;
    void handleFetchCustomJWTResult(Approov.TokenFetchResult r) throws ApproovException;
    boolean handleInterceptorShouldProcessRequest(ApproovRequest request) throws ApproovException;
    boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult r, String url) throws ApproovException;
    boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult r, String header) throws ApproovException;
    ApproovRequest handleInterceptorProcessedRequest(ApproovRequest request, ApproovRequestMutations changes) throws ApproovException;
    boolean handlePinningShouldProcessRequest(String hostname);
}
```

Install a mutator with [`setServiceMutator`](#setservicemutatorapproovservicemutator-mutator).

---

## ApproovDefaultMessageSigning

`ApproovDefaultMessageSigning` is an `ApproovServiceMutator` that adds HTTP message signatures (RFC 9421) to gRPC requests by implementing `handleInterceptorProcessedRequest`. It adds `Signature` and `Signature-Input` metadata entries once an Approov token header has been added to the request.

```java
ApproovDefaultMessageSigning.SignatureParametersFactory factory =
    ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();   // install (ES256) by default
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));
```

The default factory signs `@method` and `@target-uri`, the Approov token and trace-ID headers, and the `Authorization` header when present, using install signing (`ecdsa-p256-sha256`). Switch to account signing (`hmac-sha256`) with `factory.setUseAccountMessageSigning()`. Per-host factories can be registered with `putHostFactory(host, factory)`.

**No body digest for gRPC.** gRPC requests carry no buffered body at the interceptor layer, so a `Content-Digest` cannot be generated; the default factory configures no body digest. Configuring one with `setBodyDigestConfig(alg, /* required */ true)` will therefore always fail closed.

### Fail-open policy

Message signing is **fail-open** (TESTING_REQUIREMENTS.md §5). The signer fails **closed** — propagating the error and aborting the request — in only two cases:

1. the configured signing **algorithm is unsupported** (throws `ApproovException`); and
2. a body digest configured as **required** cannot be generated (not applicable to gRPC, which has no accessible body).

For **all other** signing failures the request proceeds **unsigned** (no signature headers added) and the reason is logged at **error** level. These fail-open cases are: the SDK cannot provide a signature (`getInstallMessageSignature` / `getAccountMessageSignature` returns `null`, e.g. the device does not support key pair generation, no account key is available yet, or attestation/token fetch did not succeed); a base64 decode failure of the returned signature; and an ASN.1/DER decode failure of an ES256 signature. The backend remains the enforcement point: a request that arrives without a valid signature is rejected there.

The ES256 ASN.1/DER signature is decoded to raw `r||s` (32 + 32 byte) form by a self-contained byte parser; no third-party ASN.1 library (such as BouncyCastle) is used.
