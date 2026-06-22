# Approov Service for Android Java Clients using gRPC

![Java](https://img.shields.io/badge/Java-8%2B-007396?logo=openjdk&logoColor=white)
![Android](https://img.shields.io/badge/Android-minSdk%2023-3DDC84?logo=android&logoColor=white)
![Maven Central](https://img.shields.io/maven-central/v/io.approov/service.grpc?logo=apachemaven&logoColor=white&label=Maven%20Central)
![Message Signing](https://img.shields.io/badge/Message%20Signing-RFC%209421-1f6feb)
![Build](https://github.com/approov/approov-service-android-java-grpc/actions/workflows/build_and_test.yml/badge.svg)

A wrapper for the [Approov SDK](https://github.com/approov/approov-android-sdk) to enable easy integration when using [`gRPC-Java`](https://github.com/grpc/grpc-java) on Android for making the API calls that you wish to protect with Approov. In order to use this you will need a trial or paid [Approov](https://www.approov.io) account.

This page provides the steps for integrating Approov into your app. Additionally, a step-by-step tutorial guide using our [Shapes App Example](https://github.com/approov/quickstart-android-java-grpc/blob/master/SHAPES-EXAMPLE.md) is also available.

To follow this guide you should have received an onboarding email for a trial or paid Approov account.

## ADDING APPROOV SERVICE DEPENDENCY
The Approov integration is available via [`Maven Central`](https://mvnrepository.com/repos/central). This allows inclusion into the project by simply specifying a dependency in the `build.gradle` file for the app:

```groovy
implementation("io.approov:service.grpc:3.5.4")
```

Make sure you do a Gradle sync after making these changes.

This package is an open-source wrapper layer that allows you to easily use Approov with `gRPC-Java`. It has a further dependency on the closed-source [Approov SDK](https://central.sonatype.com/artifact/io.approov/approov-android-sdk).

## MANIFEST CHANGES
The following app permissions need to be available in the manifest to use Approov:

```xml
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

Note that the minimum SDK version you can use with the Approov package is 23 (Android 6.0).

Please read the [Targeting Android 11 and Above](https://approov.io/docs/latest/approov-usage-documentation/#targeting-android-11-and-above) section of the reference documentation if targeting Android 11 (API level 30) or above.

## INITIALIZING APPROOV SERVICE
In order to use the `ApproovService` you must initialize it when your app is created, usually in the `onCreate` method of your custom `Application` class. Initialization can fail (bad config, SDK error), so wrap it in a `try/catch` and make sure your app survives a failure rather than crashing:

```java
import android.util.Log;
import io.approov.service.grpc.ApproovService;
import java.util.UUID;

public class YourApp extends Application {
    private static final String TAG = "YourApp";

    @Override
    public void onCreate() {
        super.onCreate();

        // An app-generated id used to correlate this install/session across your own app
        // logs and your backend. Use a UUID, or any session/user identifier you already
        // have — it is NOT an Approov secret.
        String correlationId = UUID.randomUUID().toString();

        try {
            ApproovService.initialize(getApplicationContext(), "<enter-your-config-string-here>");
            // Confirm Approov is actually active before treating it as enabled, then log
            // identifiers for correlation / observability.
            if (ApproovService.isApproovEnabled()) {
                Log.i(TAG, "Approov initialized; deviceID=" + ApproovService.getDeviceID()
                        + " session=" + correlationId);
            } else {
                Log.w(TAG, "Approov initialized in bypass mode (no protection); session=" + correlationId);
            }
        } catch (Exception e) {
            // Initialization failed — log it and continue UNPROTECTED so the app still works.
            // Re-initializing with an empty config string enters bypass mode (initialized, but
            // no Approov token injection, pinning, or secret substitution).
            Log.e(TAG, "Approov init failed (session=" + correlationId + "); continuing unprotected", e);
            ApproovService.initialize(getApplicationContext(), "");
        }
    }
}
```

The `<enter-your-config-string-here>` is a custom string that configures your Approov account access. This will have been provided in your Approov onboarding email.

On success the example logs the Approov **device ID** (`getDeviceID()`) and an **app-generated session/correlation id** (a UUID, or any session/user identifier you use) so a given install can be correlated across your app logs, backend, and the Approov [Live Metrics](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). If initialization fails, the example re-initializes with an empty config so the app keeps working — but those requests go out **without Approov protection**, so treat the backend as the enforcement point.

## USING APPROOV SERVICE
### Creating Pinned gRPC Channels
Create secure pinned gRPC channels by using the `ApproovChannelBuilder` instead of the usual `ManagedChannelBuilder`:

```java
import io.approov.service.grpc.ApproovChannelBuilder;
import io.grpc.ManagedChannel;

String host = "grpc.example.com";
int port = 443;
ManagedChannel channel = ApproovChannelBuilder.forAddress(host, port).build();
```

### Adding the Approov Interceptor
Add Approov-enabled remote procedure call stubs by adding an `ApproovClientInterceptor`, which adds the `Approov-Token` header and may also substitute header values when using secrets protection:

```java
import io.approov.service.grpc.ApproovClientInterceptor;

// Get calling stub
ExampleGrpc.ExampleBlockingStub stub = ExampleGrpc.newBlockingStub(channel);
stub = stub.withInterceptors(new ApproovClientInterceptor(channel));
```

For gRPC services configured to be protected with an Approov token, this adds the `Approov-Token` header and pins the connection. This may also substitute header values when using secrets protection.

Approov errors will generate an `ApproovException`, which is a subclass of `Exception`. This may be further specialized into:

* `ApproovRejectionException`: Attestation has been rejected. The `ARC` and `rejectionReasons` may contain specific device information to help with troubleshooting.
* `ApproovNetworkException`: Temporary networking issue; the request should generally be retried.

## CHECKING IT WORKS
Initially you won't have set which API domains to protect, so any protected call will not add anything. It will have called Approov though and made contact with the Approov cloud service. You will see logging from Approov saying `UNKNOWN_URL`.

Your Approov onboarding email should contain a link allowing you to access [Live Metrics Graphs](https://approov.io/docs/latest/approov-usage-documentation/#metrics-graphs). After you've run your app with Approov integration you should be able to see the results in the live metrics within a minute or so. At this stage you could even release your app to get details of your app population and the attributes of the devices they are running upon.

## NEXT STEPS
To actually protect your APIs and/or secrets there are some further steps. Approov provides two different options for protection:

* [API PROTECTION](https://github.com/approov/quickstart-android-java-grpc/blob/master/API-PROTECTION.md): You should use this if you control the backend API(s) being protected and are able to modify them to ensure that a valid Approov token is being passed by the app. An [Approov Token](https://approov.io/docs/latest/approov-usage-documentation/#approov-tokens) is a short-lived cryptographically signed JWT proving the authenticity of the call.

* [SECRETS PROTECTION](https://github.com/approov/quickstart-android-java-grpc/blob/master/SECRETS-PROTECTION.md): This allows app secrets, including API keys for 3rd party services, to be protected so that they no longer need to be included in the released app code. These secrets are only made available to valid apps at runtime.

Note that it is possible to use both approaches side-by-side in the same app.

---

## Useful Links

- [Approov SDK](https://github.com/approov/approov-android-sdk)
- [gRPC-Java Documentation](https://grpc.io/docs/languages/java/)
- [Approov Website](https://www.approov.io)
- [Quickstart Guide](https://github.com/approov/quickstart-android-java-grpc)
- [Shapes App Example](https://github.com/approov/quickstart-android-java-grpc/blob/master/SHAPES-EXAMPLE.md)
- [Changelog](CHANGELOG.md)
- [Reference Documentation](REFERENCE.md)
- [Usage Guide](USAGE.md)
