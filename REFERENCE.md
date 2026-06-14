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

### setProceedOnNetworkFail(boolean proceed)

Controls whether network calls should proceed when Approov cannot fetch a token due to network errors.

```java
ApproovService.setProceedOnNetworkFail(true);
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

### getMessageSignature(String message)

Gets the signature for the given message using the account-specific message signing key.

```java
String signature = ApproovService.getMessageSignature("message");
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

An interceptor that automatically mutates request metadata to inject Approov tokens and perform secure string substitutions.

```java
ApproovClientInterceptor interceptor = new ApproovClientInterceptor(channel);
```
