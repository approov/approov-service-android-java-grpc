# Approov Service for Android Java Clients using gRPC

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`gRPC-Java`](https://github.com/grpc/grpc-java) on Android for making API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

## Adding Approov Service Dependency

The Approov integration is available via [`Maven Central`](https://mvnrepository.com/repos/central). This allows inclusion into the project by simply specifying a dependency in the `build.gradle` file for the app:

```groovy
implementation("io.approov:service.grpc:3.5.3")
```

This package is an open-source wrapper layer that allows you to easily use Approov with `gRPC-Java`. It has a further dependency on the closed-source [Approov SDK](https://github.com/approov/approov-android-sdk).

## Manifest Changes

The following app permissions need to be available in the manifest to use Approov:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

Note that the minimum SDK version you can use with the Approov package is 21 (Android 5.0).

Please read the [Targeting Android 11 and Above](https://approov.io/docs/latest/approov-usage-documentation/#targeting-android-11-and-above) section of the reference documentation if targeting Android 11 (API level 30) or above.

## Using Approov Service

In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method of your custom `Application` class:

```java
import io.approov.service.grpc.ApproovService;

public class YourApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ApproovService.initialize(getApplicationContext(), "<enter-your-config-string-here>");
    }
}
```

The `<enter-your-config-string-here>` is a custom string that configures your Approov account access. This will have been provided in your Approov onboarding email.

### Creating Pinned gRPC Channels

You can then create secure pinned gRPC channels by using the `ApproovChannelBuilder` instead of the usual `ManagedChannelBuilder`:

```java
import io.approov.service.grpc.ApproovChannelBuilder;
import io.grpc.ManagedChannel;

String host = "grpc.example.com";
int port = 443;
ManagedChannel channel = ApproovChannelBuilder.forAddress(host, port).build();
```

### Adding Approov Interceptor

Add Approov-enabled remote procedure call stubs by adding an `ApproovClientInterceptor` which adds the `Approov-Token` header and may also substitute header values when using secrets protection:

```java
import io.approov.service.grpc.ApproovClientInterceptor;

// Get calling stub
ExampleGrpc.ExampleBlockingStub stub = ExampleGrpc.newBlockingStub(channel);
stub = stub.withInterceptors(new ApproovClientInterceptor(channel));
```

## Error Handling

Approov errors will generate an `ApproovException`, which is a subclass of `Exception`. This may be further specialized into:

* `ApproovRejectionException`: Attestation has been rejected. The `ARC` and `rejectionReasons` may contain specific device information to help with troubleshooting.
* `ApproovNetworkException`: Temporary networking issue; the request should generally be retried.

## Quickstart and Sample App

- **Quickstart Guide**: For a step-by-step walk-through of integrating this service layer, see the [Approov gRPC Quickstart](https://github.com/approov/quickstart-android-java-grpc).
- **Shapes App Example**: A complete working example integration is available in the [Shapes App Example Tutorial](https://github.com/approov/quickstart-android-java-grpc/blob/master/SHAPES-EXAMPLE.md), which demonstrates token fetching, secure string substitution, and pinning in a real gRPC application.

---

# Reference

Please see the [REFERENCE.md](REFERENCE.md) for more information on the Approov Service for Android gRPC APIs.

# Usage

Please see the [USAGE.md](USAGE.md) for more information on how to configure and use this wrapper.
