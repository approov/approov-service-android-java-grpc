# Usage

This document describes the features and functionality of the Approov Service for Android Java Clients using gRPC. It explains how to initialize the service, use the `ApproovChannelBuilder` and `ApproovClientInterceptor` for protected gRPC requests, and configure optional features such as token binding, secure string substitution, custom headers, and other SDK helper methods. For a basic integration example, please refer to the [Quickstart guide](README.md).

## Basic Integration

For most integrations, you initialize `ApproovService` once at app startup, build an `ApproovChannelBuilder` channel, and configure your generated gRPC stubs with the `ApproovClientInterceptor`.

```java
import io.approov.service.grpc.ApproovService;
import io.approov.service.grpc.ApproovChannelBuilder;
import io.approov.service.grpc.ApproovClientInterceptor;
import io.grpc.ManagedChannel;

// Initialize the Approov service
ApproovService.initialize(getApplicationContext(), "<config-string>");

// Create the channel
String host = "grpc.example.com";
int port = 443;
ManagedChannel channel = ApproovChannelBuilder.forAddress(host, port).build();

// Provide the channel and interceptors to the generated client stubs
ExampleGrpc.ExampleBlockingStub stub = ExampleGrpc.newBlockingStub(channel);
stub = stub.withInterceptors(new ApproovClientInterceptor(channel));
```

---

## Empty Config Initialization

You can initialize the `ApproovService` with an empty configuration string if you want to use the service layer without active Approov protection. This is useful when you want to bypass Approov processing (e.g., during development or testing against local staging environments).

```java
// Initialize with an empty string to operate in bypass mode
ApproovService.initialize(getApplicationContext(), "");
```

When initialized with an empty configuration, the service layer operates as a plain pass-through. It will not perform token injection or secure string substitution, and dynamic pinning is skipped (though standard host certificate validation is still enforced by default).

---

## Token Binding

Token Binding allows you to bind the Approov token to a specific piece of data, such as an authorization header or access token.

```java
// Bind the Approov token to the Authorization header
ApproovService.setBindingHeader("authorization");
```

If the value of the binding header is present in the request metadata, the SDK hashes the value and includes it in the `pay` claim of the issued Approov token. If the binding value changes, the SDK automatically fetches a new token with the updated binding on the next request.

---

## Custom Token Headers and Prefixes

By default, the Approov token is added to the `Approov-Token` header with no prefix. You can customize the header name and prepend an optional prefix (such as `"Bearer "`) using `setApproovHeader`:

```java
// Customize token header and prefix
ApproovService.setApproovHeader("authorization", "Bearer ");
```

---

## Secure String Substitution

You can use Approov to protect app secrets (such as API keys) by storing them securely in the Approov cloud and substituting them into headers at runtime.

First, register the header name and optional prefix (e.g. `"Bearer "`) that should be substituted:

```java
// Register the authorization header for substitution
ApproovService.addSubstitutionHeader("authorization", "Bearer ");
```

When a request is sent containing that header, the service layer uses the original header value (excluding the prefix) as a lookup key to retrieve the secure string from Approov. If successful, it substitutes the secure string value back into the header.

To remove a registered substitution header:

```java
ApproovService.removeSubstitutionHeader("authorization");
```

---

## Exclusion URLs

You can exclude specific requests from Approov request mutation. A request whose reconstructed URL matches an exclusion regex is forwarded **without** an Approov token, trace ID, message signing, or secure string substitution. Certificate pinning may still be applied to the host.

Because gRPC has no request URL at the interceptor layer, the regex is matched against `"https://<hostname><path>"`, where `<path>` is the RPC path of the form `/package.Service/Method`.

```java
// Skip Approov mutation for health-check RPCs on this host
ApproovService.addExclusionURLRegex("https://grpc.example.com/grpc.health\\..*");

// Later, revert to standard processing
ApproovService.removeExclusionURLRegex("https://grpc.example.com/grpc.health\\..*");
```

---

## Trace ID Header

By default the service layer adds an `Approov-TraceID` header to protected requests to aid debugging. You can change the header name, or disable it entirely by passing `null`:

```java
ApproovService.setApproovTraceIDHeader("X-My-Trace");   // custom header name
ApproovService.setApproovTraceIDHeader(null);           // disable the trace ID header
```

---

## Token Status Fallback

When a real Approov token cannot be obtained, you can have the service layer inject the fetch status string into the token header instead, giving the backend visibility into why a token was unavailable:

```java
ApproovService.setUseApproovStatusIfNoToken(true);
```

---

## Custom Mutators / Network Failure Behavior

The legacy `setProceedOnNetworkFail` method is **deprecated and is now a no-op**. To customize how the interceptor reacts to a networking failure (or any other token fetch status), install a custom `ApproovServiceMutator`:

```java
ApproovService.setServiceMutator(new ApproovServiceMutator() {
    @Override
    public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult results, String url)
            throws ApproovException {
        // Proceed without a token on a networking failure instead of failing closed
        switch (results.getStatus()) {
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                return false; // forward the request without a token
            default:
                // fall back to the default fail-closed handling
                return ApproovServiceMutator.DEFAULT.handleInterceptorFetchTokenResult(results, url);
        }
    }
});
```

> [!WARNING]
> Proceeding on network failures might allow connections before dynamic pins have been received, potentially opening the channel to a Man-in-the-Middle (MITM) attack.

> [!NOTE]
> The custom mutator is reset to the default on every successful `initialize` call, so install it after initialization.

---

## Message Signing

Message signing (RFC 9421) adds `Signature` and `Signature-Input` metadata to gRPC requests, allowing the backend to verify request integrity. It is provided by `ApproovDefaultMessageSigning`, which is itself an `ApproovServiceMutator`, so you enable it via `setServiceMutator`:

```java
import io.approov.service.grpc.ApproovDefaultMessageSigning;

// Install message signing (ES256) — the default factory
ApproovDefaultMessageSigning.SignatureParametersFactory factory =
    ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory();
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));
```

To use account message signing (HMAC-SHA256) instead of install signing:

```java
ApproovDefaultMessageSigning.SignatureParametersFactory factory =
    ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
        .setUseAccountMessageSigning();
ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));
```

The default factory signs `@method`, `@target-uri`, the Approov token and trace-ID headers, and the `Authorization` header when present. Signing is only performed once an Approov token has been added to the request.

> [!NOTE]
> gRPC requests carry no buffered body at the interceptor layer, so **no `Content-Digest` body digest is produced for gRPC**.

> [!NOTE]
> Message signing is **fail-open**: if the SDK cannot provide a signature (or the signature cannot be decoded), the request proceeds **unsigned** and the reason is logged at error level. The only fail-closed case applicable to gRPC is configuring an **unsupported signing algorithm**, which throws an `ApproovException`. The backend remains the enforcement point for signatures.

> [!NOTE]
> Install the message signing mutator after initialization, as the mutator is reset to the default on every successful `initialize` call.
