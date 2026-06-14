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

## Proceed on Network Failure

By default, if the service layer cannot fetch an Approov token due to network issues (such as `noNetwork` or `poorNetwork`), the request is blocked and an `ApproovNetworkException` is thrown. You can allow requests to proceed anyway (without the token header) by setting `setProceedOnNetworkFail`:

```java
// Proceed even if token fetch fails due to network issues
ApproovService.setProceedOnNetworkFail(true);
```

> [!WARNING]
> Use this with caution, as proceeding on network failures might allow connections before dynamic pins have been received, potentially opening the channel to a Man-in-the-Middle (MITM) attack.
