//
// MIT License
// 
// Copyright (c) 2016-present, Critical Blue Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
// 
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.grpc;

import android.content.Context;
import android.util.Log;

import com.criticalblue.approovsdk.Approov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.grpc.Metadata;

// ApproovService provides a mediation layer to the Approov SDK itself
public class ApproovService {
    /** logging tag */
    private static final String TAG = "ApproovService";

    /** default header that will be added to Approov enabled requests */
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    /** default prefix to be added before the Approov token by default */
    private static final String APPROOV_TOKEN_PREFIX = "";

    /** default header that will be used to carry the optional Approov trace ID */
    private static final String APPROOV_TRACE_ID_HEADER = "Approov-TraceID";

    /** true if the Approov SDK initialized okay */
    private static boolean initialized = false;

    /** Initialization configuration string. NOTE this must only ever be written to ONCE since Approov SDK can only
     * be initialized once */
    private static String approovConfigString = null;

    /** true if the interceptor should proceed on network failures and not add an Approov token */
    private static boolean proceedOnNetworkFail = false;

    /** header to be used to send Approov tokens */
    private static String approovTokenHeader = APPROOV_TOKEN_HEADER;

    /** any prefix String to be added before the transmitted Approov token */
    private static String approovTokenPrefix = APPROOV_TOKEN_PREFIX;

    /** any header to be used for binding in Approov tokens or null if not set */
    private static String bindingHeader = null;

    /** header to be used to send the optional Approov trace ID, or null if disabled */
    private static String approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;

    /** true if the fetch status string should be injected into the token header when no real token is available */
    private static boolean useApproovStatusIfNoToken = false;

    /** the active service mutator, used to override default decision/processing behavior */
    private static ApproovServiceMutator serviceMutator = ApproovServiceMutator.DEFAULT;

    /** map of headers that should have their values substituted for secure strings, mapped to their required
     * prefixes */
    private static final Map<String, String> substitutionHeaders;
    static {
        substitutionHeaders = new HashMap<>();
    }

    /** map of URL regexs that should be excluded from any Approov protection, mapped to the compiled pattern */
    private static final Map<String, Pattern> exclusionURLRegexs;
    static {
        exclusionURLRegexs = new HashMap<>();
    }

    /** Private constructor to prevent instantiation as this is a static only class */
    private ApproovService() {}

    /**
     * Initializes the Approov service with an account configuration and an optional comment.
     *
     * Per TESTING_REQUIREMENTS §1 the service layer never short-circuits an initialization call
     * carrying a non-empty config based on its own internal state: every non-empty config (including
     * the same config with a different comment, or a different config) is forwarded directly to the
     * native Approov SDK. If the native SDK throws (e.g. a different config triggers an
     * {@link IllegalStateException}) the failure is surfaced and the service-layer state is left
     * completely unchanged. If the native SDK confirms success (including returning {@code false} for
     * an already-initialized same config) the service-layer state is reset and re-applied — including
     * resetting the custom service mutator to the default. An empty config after a valid config is
     * the only case that is ignored without being forwarded.
     *
     * @param context the Application context
     * @param config the configuration string, or empty for no SDK initialization
     * @param comment the comment string, or null for no comment (supports {@code reinit...} / {@code options:...})
     */
    public static synchronized void initialize(Context context, String config, String comment) {
        if (config == null) {
            config = "";
        }

        // §1 Empty Configuration after Valid Configuration: once initialized with a valid config,
        // ignore any subsequent empty config initialization and do NOT forward it to the SDK.
        if (isApproovEnabled() && config.isEmpty()) {
            Log.d(TAG, "ApproovService already initialized with a valid config; ignoring empty configuration");
            return;
        }

        // §1 Configuration Options and Forwarding Requirement: forward all non-empty configs to the
        // native SDK without any internal short-circuit. State is only modified after the SDK confirms
        // success, preserving the current operating mode (protected or bypass) on failure.
        if (!config.isEmpty()) {
            try {
                boolean sdkInitialized = Approov.initialize(context.getApplicationContext(), config, "auto", comment);
                if (!sdkInitialized) {
                    // §1 Same Config Re-initialization: the SDK returned false (already initialized
                    // with the same config); log and treat as success.
                    Log.d(TAG, "Approov SDK already initialized");
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            } catch (IllegalStateException e) {
                // §1 Different Non-empty Config Re-initialization: surface as a rejection.
                Log.e(TAG, "Approov initialization failed: " + e.getMessage());
                throw e; // service-layer state NOT modified — prior operating mode preserved
            }
            Approov.setUserProperty("approov-service-grpc/" + BuildConfig.APPROOV_SERVICE_VERSION);
        }

        // §1 Service-Layer State Only Updated On Success / Service Mutator Reset: now that the
        // platform SDK has confirmed success (or we are in empty-config bypass mode), reset the
        // service-layer state. This includes resetting the custom service mutator to the default
        // on every successful initialization so custom overrides do not persist across boundaries.
        initialized = false;
        approovConfigString = null;
        proceedOnNetworkFail = false;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        bindingHeader = null;
        useApproovStatusIfNoToken = false;
        serviceMutator = ApproovServiceMutator.DEFAULT;
        substitutionHeaders.clear();
        exclusionURLRegexs.clear();
        initialized = true;
        approovConfigString = config;
    }

    /**
     * Initializes the Approov service with an account configuration.
     *
     * @param context the Application context
     * @param config the configuration string, or empty for no SDK initialization
     */
    public static void initialize(Context context, String config) {
        // default uses null comment
        initialize(context, config, null);
    }

    /**
     * Indicates whether the service layer has been initialized.
     *
     * @return true if the service layer has been initialized, false otherwise
     */
    public static synchronized boolean isInitialized() {
        return initialized;
    }

    /**
     * Indicates whether Approov protection is enabled for this service layer
     * instance. If initialization used an empty config string then the layer is
     * initialized but Approov protection is bypassed.
     *
     * @return true if Approov protection is enabled, false otherwise
     */
    public static synchronized boolean isApproovEnabled() {
        return initialized && (approovConfigString != null) && !approovConfigString.isEmpty();
    }

    /**
     * Resets the ApproovService state for testing.
     * This is a testing requirement and has no production use case.
     */
    static synchronized void resetForTesting() {
        initialized = false;
        approovConfigString = null;
        proceedOnNetworkFail = false;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        approovTraceIDHeader = APPROOV_TRACE_ID_HEADER;
        bindingHeader = null;
        useApproovStatusIfNoToken = false;
        serviceMutator = ApproovServiceMutator.DEFAULT;
        substitutionHeaders.clear();
        exclusionURLRegexs.clear();
    }

    /**
     * Sets a flag indicating if the network interceptor should proceed anyway if it is not possible to obtain an
     * Approov token due to a networking failure. If this is set then your backend API can receive calls without the
     * expected Approov token header being added, or without header parameter substitutions being made.
     * Note that this should be used with caution because it may allow a connection to be established before any dynamic
     * pins have been received via Approov, thus potentially opening the channel to a MitM.
     *
     * @param proceed is true if Approov networking fails should allow continuation
     * @deprecated No longer used internally. Use {@link #setServiceMutator(ApproovServiceMutator)} to
     *             customize the behavior on a networking failure. This method is now a no-op.
     */
    @Deprecated
    public static synchronized void setProceedOnNetworkFail(boolean proceed) {
        Log.d(TAG, "setProceedOnNetworkFail (deprecated no-op) " + proceed);
    }

    /**
     * Gets whether the interceptor should proceed on networking failures.
     *
     * @return true if the interceptor should proceed on a networking failure
     * @deprecated No longer used internally. Use {@link #setServiceMutator(ApproovServiceMutator)} to
     *             customize the behavior on a networking failure.
     */
    @Deprecated
    static synchronized boolean getProceedOnNetworkFail() {
        return proceedOnNetworkFail;
    }

    /**
     * Sets a custom service mutator that can override the default fail-closed interceptor behavior
     * and the default attestation-result handling. Passing null restores the default mutator.
     *
     * @param mutator the mutator to install, or null to restore the default behavior
     */
    public static synchronized void setServiceMutator(ApproovServiceMutator mutator) {
        Log.d(TAG, "setServiceMutator " + mutator);
        serviceMutator = (mutator == null) ? ApproovServiceMutator.DEFAULT : mutator;
    }

    /**
     * Gets the currently active service mutator.
     *
     * @return the active service mutator (never null)
     */
    public static synchronized ApproovServiceMutator getServiceMutator() {
        return serviceMutator;
    }

    /**
     * Sets the header that the optional Approov trace ID is added on. By default the trace ID is
     * provided on "Approov-TraceID". Pass null to disable emission of the trace ID header.
     *
     * @param header the header on which to place the Approov trace ID, or null to disable
     */
    public static synchronized void setApproovTraceIDHeader(String header) {
        Log.d(TAG, "setApproovTraceIDHeader " + header);
        approovTraceIDHeader = header;
    }

    /**
     * Gets the header on which the optional Approov trace ID is placed.
     *
     * @return the trace ID header name, or null if disabled
     */
    static synchronized String getApproovTraceIDHeader() {
        return approovTraceIDHeader;
    }

    /**
     * Sets whether the Approov fetch status string should be injected into the token header when a
     * real Approov token cannot be obtained. This provides visibility to the backend into the reason
     * a token was unavailable.
     *
     * @param shouldUse true to inject the fetch status when no token is available
     */
    public static synchronized void setUseApproovStatusIfNoToken(boolean shouldUse) {
        Log.d(TAG, "setUseApproovStatusIfNoToken " + shouldUse);
        useApproovStatusIfNoToken = shouldUse;
    }

    /**
     * Gets whether the Approov fetch status string is injected into the token header when no real
     * token is available.
     *
     * @return true if the fetch status is injected when no token is available
     */
    static synchronized boolean getUseApproovStatusIfNoToken() {
        return useApproovStatusIfNoToken;
    }

    /**
     * Adds an exclusion URL regular expression. Requests whose reconstructed URL matches the regex are
     * forwarded by the interceptor without any Approov request mutation (no token, trace ID, message
     * signing, or secure string substitution). Note that certificate pinning may still be applied.
     *
     * For gRPC, the reconstructed URL is "https://&lt;hostname&gt;&lt;path&gt;" when an RPC path is
     * available, otherwise the bare hostname.
     *
     * @param urlRegex the regular expression to add
     */
    public static synchronized void addExclusionURLRegex(String urlRegex) {
        if (urlRegex == null)
            return;
        try {
            Pattern pattern = Pattern.compile(urlRegex);
            exclusionURLRegexs.put(urlRegex, pattern);
            Log.d(TAG, "addExclusionURLRegex " + urlRegex);
        } catch (PatternSyntaxException e) {
            Log.e(TAG, "addExclusionURLRegex " + urlRegex + " rejected, exclusion NOT added: " + e.getMessage());
        }
    }

    /**
     * Removes an exclusion URL regular expression previously added using addExclusionURLRegex.
     *
     * @param urlRegex the regular expression to remove
     */
    public static synchronized void removeExclusionURLRegex(String urlRegex) {
        if (urlRegex == null)
            return;
        Log.d(TAG, "removeExclusionURLRegex " + urlRegex);
        exclusionURLRegexs.remove(urlRegex);
    }

    /**
     * Gets a copy of the map of exclusion URL regexes, mapped to their compiled patterns.
     *
     * @return a snapshot map of exclusion regex strings to compiled patterns
     */
    static synchronized Map<String, Pattern> getExclusionURLRegexs() {
        return new HashMap<>(exclusionURLRegexs);
    }

    /**
     * Sets a development key indicating that the app is a development version and it should
     * pass attestation even if the app is not registered or it is running on an emulator. The
     * development key value can be rotated at any point in the account if a version of the app
     * containing the development key is accidentally released. This is primarily
     * used for situations where the app package must be modified or resigned in
     * some way as part of the testing process.
     *
     * @param devKey is the development key to be used
     * @throws ApproovException if there was a problem
     */
    public static synchronized void setDevKey(String devKey) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("setDevKey: SDK not initialized");
        }
        try {
            Approov.setDevKey(devKey);
            Log.d(TAG, "setDevKey");
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Adds the name of a header which should be subject to secure strings substitution. This
     * means that if the header is present then the value will be used as a key to look up a
     * secure string value which will be substituted into the header value instead. This allows
     * easy migration to the use of secure strings. Note that this should be done on initialization
     * rather than for every request as it will require a new OkHttpClient to be built. A required
     * prefix may be specified to deal with cases such as the use of "Bearer " prefixed before values
     * in an authorization header.
     *
     * @param header is the header to be marked for substitution
     * @param requiredPrefix is any required prefix to the value being substituted or null if not required
     */
    public static synchronized void addSubstitutionHeader(String header, String requiredPrefix) {
        if (initialized) {
            Log.d(TAG, "addSubstitutionHeader " + header + ", " + requiredPrefix);
            if (requiredPrefix == null)
                substitutionHeaders.put(header, "");
            else
                substitutionHeaders.put(header, requiredPrefix);
        }
    }

    /**
     * Removes a header previously added using addSubstitutionHeader.
     *
     * @param header is the header to be removed for substitution
     */
    public static synchronized void removeSubstitutionHeader(String header) {
        Log.d(TAG, "removeSubstitutionHeader " + header);
        substitutionHeaders.remove(header);
    }

    /**
     * Prefetches an Approov token in the background.
     *
     * @deprecated Obsolete. The platform SDK manages prefetching automatically.
     */
    @Deprecated
    public static synchronized void prefetch() {
        Log.i(TAG, "prefetch is obsolete and does nothing");
    }

    /**
     * Performs a precheck to determine if the app will pass attestation. This requires secure
     * strings to be enabled for the account, although no strings need to be set up. This will
     * likely require network access so may take some time to complete. It may throw ApproovException
     * if the precheck fails or if there is some other problem. ApproovRejectionException is thrown
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where a
     * user initiated retry of the operation should be allowed. An ApproovRejectionException may provide
     * additional information about the cause of the rejection.
     *
     * @throws ApproovException if there was a problem
     */
    public static void precheck() throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("precheck: SDK not initialized");
        }
        // try and fetch a non-existent secure string in order to check for a rejection
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait("precheck-dummy-key", null);
            Log.d(TAG, "precheck: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // route the returned Approov status through the active service mutator
        getServiceMutator().handlePrecheckResult(approovResults);
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional prefix String (such as "Bearer ").
     * By default the token is provided on "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    public static synchronized void setApproovHeader(String header, String prefix) {
        Log.d(TAG, "setApproovHeader " + header + ", " + prefix);
        approovTokenHeader = header;
        approovTokenPrefix = prefix;
    }

    /**
     * Gets the Approov token header.
     *
     * @return header on which to place the Approov token
     */
    static synchronized String getApproovHeader() {
        return approovTokenHeader;
    }

    /**
     * Gets the Approov token header value prefix.
     *
     * @return prefix to add to the Approov token header value
     */
    static synchronized String getApproovPrefix() {
        return approovTokenPrefix;
    }

    /**
     * Sets a binding header that must be present on all requests using the Approov service. A
     * header should be chosen whose value is unchanging for most requests (such as an
     * Authorization header). A hash of the header value is included in the issued Approov tokens
     * to bind them to the value. This may then be verified by the backend API integration. This
     * method should typically only be called once.
     *
     * @param header is the header to use for Approov token binding
     */
    public static synchronized void setBindingHeader(String header) {
        Log.d(TAG, "setBindingHeader " + header);
        bindingHeader = header;
    }

    /**
     * Gets any current binding header.
     *
     * @return binding header or null if not set
     */
    static synchronized String getBindingHeader() {
        return bindingHeader;
    }

    /**
     * Gets the device ID used by Approov to identify the particular device that the SDK is running on. Note
     * that different Approov apps on the same device will return a different ID. Moreover, the ID may be
     * changed by an uninstall and reinstall of the app.
     *
     * @return String of the device ID
     * @throws ApproovException if there was a problem
     */
    public static String getDeviceID() throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("getDeviceID: SDK not initialized");
        }
        try {
            String deviceID = Approov.getDeviceID();
            Log.d(TAG, "getDeviceID: " + deviceID);
            return deviceID;
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
    }

    /**
     * Directly sets the data hash to be included in subsequently fetched Approov tokens. If the hash is
     * different from any previously set value then this will cause the next token fetch operation to
     * fetch a new token with the correct payload data hash. The hash appears in the
     * 'pay' claim of the Approov token as a base64 encoded string of the SHA256 hash of the
     * data. Note that the data is hashed locally and never sent to the Approov cloud service.
     *
     * @param data is the data to be hashed and set in the token
     * @throws ApproovException if there was a problem
     */
    public static void setDataHashInToken(String data) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("setDataHashInToken: SDK not initialized");
        }
        try {
            Approov.setDataHashInToken(data);
            Log.d(TAG, "setDataHashInToken");
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }
    }

    /**
     * Performs an Approov token fetch for the given URL. This should be used in situations where it
     * is not possible to use the networking interception to add the token. This will
     * likely require network access so may take some time to complete. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovNetworkException for
     * networking issues wher a user initiated retry of the operation should be allowed. Note that
     * the returned token should NEVER be cached by your app, you should call this function when
     * it is needed.
     *
     * @param url is the URL giving the domain for the token fetch
     * @return String of the fetched token
     * @throws ApproovException if there was a problem
     */
    public static String fetchToken(String url) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("fetchToken: SDK not initialized");
        }
        // fetch the Approov token
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchApproovTokenAndWait(url);
            Log.d(TAG, "fetchToken: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // route the returned Approov status through the active service mutator (throws on failure)
        getServiceMutator().handleFetchTokenResult(approovResults);
        // provide the Approov token result
        return approovResults.getToken();
    }

    /**
     * Gets the signature for the given message. This uses an account specific message signing key that is
     * transmitted to the SDK after a successful fetch if the facility is enabled for the account. Note
     * that if the attestation failed then the signing key provided is actually random so that the
     * signature will be incorrect. An Approov token should always be included in the message
     * being signed and sent alongside this signature to prevent replay attacks. If no signature is
     * available, because there has been no prior fetch or the feature is not enabled, then an
     * ApproovException is thrown.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature
     * @throws ApproovException if there was a problem
     * @deprecated Use {@link #getAccountMessageSignature(String)} or
     *             {@link #getInstallMessageSignature(String)} instead.
     */
    @Deprecated
    public static String getMessageSignature(String message) throws ApproovException {
        String signature = getAccountMessageSignature(message);
        if (signature == null)
            throw new ApproovException("getMessageSignature: no signature available");
        return signature;
    }

    /**
     * Gets the account message signature for the given message. This uses an account specific message
     * signing key that is transmitted to the SDK after a successful fetch if the facility is enabled
     * for the account. Returns null if no signature is available (no prior fetch, the feature is not
     * enabled, or the service layer is in bypass mode). Note that if the attestation failed then the
     * signing key provided is actually random so that the signature will be incorrect. An Approov
     * token should always be included in the message being signed and sent alongside this signature
     * to prevent replay attacks.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded message signature, or null if none is available
     */
    public static String getAccountMessageSignature(String message) {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getAccountMessageSignature: SDK not initialized");
            return null;
        }
        Log.d(TAG, "getAccountMessageSignature");
        return Approov.getMessageSignature(message);
    }

    /**
     * Gets the install message signature for the given message. This uses an install specific signing
     * key. Returns null if no signature is available (for example key pair generation is not supported
     * on the device) or if the service layer is in bypass mode. An Approov token containing the public
     * key should be included in the message being signed and sent alongside this signature.
     *
     * @param message is the message whose content is to be signed
     * @return String of the base64 encoded ASN.1 DER signature, or null if none is available
     */
    public static String getInstallMessageSignature(String message) {
        if (!isApproovEnabled()) {
            Log.e(TAG, "getInstallMessageSignature: SDK not initialized");
            return null;
        }
        Log.d(TAG, "getInstallMessageSignature");
        return Approov.getInstallMessageSignature(message);
    }

    /**
     * Fetches a secure string with the given key. If newDef is not null then a
     * secure string for the particular app instance may be defined. In this case the
     * new value is returned as the secure string. Use of an empty string for newDef removes
     * the string entry. Note that this call may require network transaction and thus may block
     * for some time, so should not be called from the UI thread. If the attestation fails
     * for any reason then an ApproovException is thrown. This will be ApproovRejectionException
     * if the app has failed Approov checks or ApproovNetworkException for networking issues where
     * a user initiated retry of the operation should be allowed. Note that the returned string
     * should NEVER be cached by your app, you should call this function when it is needed.
     *
     * @param key is the secure string key to be looked up
     * @param newDef is any new definition for the secure string, or null for lookup only
     * @return secure string (should not be cached by your app) or null if it was not defined
     * @throws ApproovException if there was a problem
     */
    public static String fetchSecureString(String key, String newDef) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("fetchSecureString: SDK not initialized");
        }
        // determine the type of operation as the values themselves cannot be logged
        String type = "lookup";
        if (newDef != null)
            type = "definition";

        // fetch any secure string keyed by the value, catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchSecureStringAndWait(key, newDef);
            Log.d(TAG, "fetchSecureString " + type + ": " + key + ", " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // route the returned Approov status through the active service mutator (throws on failure)
        getServiceMutator().handleFetchSecureStringResult(approovResults, type, key);

        return approovResults.getSecureString();
    }

    /**
     * Fetches a custom JWT with the given payload. Note that this call will require network
     * transaction and thus will block for some time, so should not be called from the UI thread.
     * If the attestation fails for any reason then an IOException is thrown. This will be
     * ApproovRejectionException if the app has failed Approov checks or ApproovNetworkException
     * for networking issues where a user initiated retry of the operation should be allowed.
     *
     * @param payload is the marshaled JSON object for the claims to be included
     * @return custom JWT string
     * @throws ApproovException if there was a problem
     */
    public static String fetchCustomJWT(String payload) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("fetchCustomJWT: SDK not initialized");
        }
        // fetch the custom JWT catching any exceptions the SDK might throw
        Approov.TokenFetchResult approovResults;
        try {
            approovResults = Approov.fetchCustomJWTAndWait(payload);
            Log.d(TAG, "fetchCustomJWT: " + approovResults.getStatus().toString());
        }
        catch (IllegalStateException e) {
            throw new ApproovException("IllegalState: " + e.getMessage());
        }
        catch (IllegalArgumentException e) {
            throw new ApproovException("IllegalArgument: " + e.getMessage());
        }

        // route the returned Approov status through the active service mutator (throws on failure)
        getServiceMutator().handleFetchCustomJWTResult(approovResults);

        return approovResults.getToken();
    }

    /**
     * Adds a header with an Approov token to the given headers and may also substitute header values to hold secure
     * string secrets. If a binding header has been specified then its hash will be set if it is present. If it is not
     * currently possible to fetch an Approov token then `ApproovException` is thrown.
     *
     * @param host the host for which to fetch the Approov token
     * @param headers the headers to which to add the Approov token
     * @deprecated Use {@link #addApproov(String, String, Metadata)} which threads the RPC path
     *             through to message signing and exclusion matching.
     */
    @Deprecated
    static void addApproov(String host, Metadata headers) throws ApproovException {
        addApproov(host, null, headers);
    }

    /**
     * Adds a header with an Approov token to the given metadata and may also substitute header values
     * to hold secure string secrets, add a trace ID header, and run any configured message signing.
     * The request is routed through the active {@link ApproovServiceMutator}, which decides at each
     * step (exclusion, token fetch, header substitution, processed-request hook) how to proceed and
     * applies the cross-platform fail-closed/fail-open semantics. The metadata is mutated in place.
     *
     * gRPC carries no URL query string or accessible body at the interceptor layer, so only the host,
     * the optional RPC path and the header-based mutations are processed.
     *
     * @param host the host for which to fetch the Approov token
     * @param path the RPC path ("/package.Service/Method") or null if unavailable
     * @param headers the metadata to which to add the Approov token (mutated in place)
     * @throws ApproovException if it is not possible to obtain or apply the required artifacts
     */
    static void addApproov(String host, String path, Metadata headers) throws ApproovException {
        // throw if we couldn't initialize the SDK
        if (!initialized) {
            throw new ApproovException("Approov not initialized");
        }

        // Bypass if Approov is not enabled - forward the request unmodified
        if (!isApproovEnabled()) {
            return;
        }

        ApproovServiceMutator mutator = getServiceMutator();
        ApproovServiceMutator.ApproovRequest request = new ApproovServiceMutator.ApproovRequest(host, path, headers);
        ApproovRequestMutations changes = new ApproovRequestMutations();

        // Exclusion check: an excluded request is forwarded without any mutation.
        if (!mutator.handleInterceptorShouldProcessRequest(request)) {
            Log.i(TAG, "Excluded, forwarding unmodified: " + host);
            return;
        }

        // Update the data hash based on any token binding header if it is available
        String bindingHeader = getBindingHeader();
        if (bindingHeader != null) {
            String headerValue = headers.get(Metadata.Key.of(bindingHeader, Metadata.ASCII_STRING_MARSHALLER));
            if (headerValue != null)
                Approov.setDataHashInToken(headerValue);
        }

        // Request an Approov token for the domain
        Approov.TokenFetchResult approovResults = Approov.fetchApproovTokenAndWait(host);

        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.i(TAG, "Token for " + host + ": " + approovResults.getLoggableToken());

        // route the token fetch status through the active service mutator. If it returns false the
        // request is forwarded without a token (e.g. unprotected/unknown URL); a failure throws.
        if (!mutator.handleInterceptorFetchTokenResult(approovResults, host)) {
            return;
        }

        // add the Approov token header, or the fetch status fallback if no real token is available
        String tokenHeaderKey = getApproovHeader();
        String tokenPrefix = getApproovPrefix();
        String token = approovResults.getToken();
        String tokenHeaderValue;
        if ((token == null || token.isEmpty()) && getUseApproovStatusIfNoToken()) {
            // §2 Token Fallback Status: surface the fetch status to the backend in place of a token.
            tokenHeaderValue = tokenPrefix + approovResults.getStatus().toString();
        } else {
            tokenHeaderValue = tokenPrefix + (token == null ? "" : token);
        }
        headers.put(Metadata.Key.of(tokenHeaderKey, Metadata.ASCII_STRING_MARSHALLER), tokenHeaderValue);
        changes.setTokenHeaderKey(tokenHeaderKey);

        // emit the trace ID header if a trace ID header name is configured
        String traceIDHeaderKey = getApproovTraceIDHeader();
        if (traceIDHeaderKey != null && !traceIDHeaderKey.isEmpty()) {
            String traceID = approovResults.getTraceID();
            if (traceID != null && !traceID.isEmpty()) {
                headers.put(Metadata.Key.of(traceIDHeaderKey, Metadata.ASCII_STRING_MARSHALLER), traceID);
                changes.setTraceIDHeaderKey(traceIDHeaderKey);
            }
        }

        // deal with any header substitutions, which may require further fetches but these should be
        // using cached results
        List<String> substitutedHeaders = new ArrayList<>();
        for (Map.Entry<String, String> entry : substitutionHeaders.entrySet()) {
            String header = entry.getKey();
            String prefix = entry.getValue();
            String value = headers.get(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER));
            if ((value != null) && value.startsWith(prefix) && (value.length() > prefix.length())) {
                approovResults = Approov.fetchSecureStringAndWait(value.substring(prefix.length()), null);
                Log.d(TAG, "Substituting header: " + header + ", " + approovResults.getStatus().toString());
                if (mutator.handleInterceptorHeaderSubstitutionResult(approovResults, header)) {
                    String secureString = approovResults.getSecureString();
                    // §2 Missing Artifacts Fallback: if substitution yields an empty value the
                    // original placeholder should remain in place.
                    if (secureString != null && !secureString.isEmpty()) {
                        headers.put(Metadata.Key.of(header, Metadata.ASCII_STRING_MARSHALLER),
                                prefix + secureString);
                        substitutedHeaders.add(header);
                    }
                }
            }
        }
        if (!substitutedHeaders.isEmpty()) {
            changes.setSubstitutionHeaderKeys(substitutedHeaders);
        }

        // call the processed-request hook for any final modifications (e.g. message signing). gRPC
        // mutates the metadata in place, so the returned request carries the same Metadata instance.
        mutator.handleInterceptorProcessedRequest(request, changes);
    }

    /**
     * Gets the list of pins for a URL domain (hostname part only). If there are no pins associated with the specific
     * hostname domain then we use any pins associated with the "*" domain. If the returned list is empty then that
     * indicates that a connection to the host is not specifically pinned.
     *
     * @param hostname is the name of the host (domain) for which to get the pins
     * @return set of strings providing the pins in base64 encoding, may be empty
     */
    static Set<String> getPins(String hostname) {
        // extract the set of valid pins for the hostname
        Set<String> pins = new HashSet<>();
        if (!isApproovEnabled())
            return pins;
        @SuppressWarnings("unchecked")
        Map<String, List<String>> allPins = Approov.getPins("public-key-sha256");
        List<String> hostPins = allPins.get(hostname);
        if ((hostPins != null) && hostPins.isEmpty())
            // if there are no pins associated with the hostname domain then we use any pins associated with the "*"
            // domain for managed trust roots (note we do not apply this to domains that are not added at all)
            hostPins = allPins.get("*");
        if (hostPins != null)
            pins.addAll(hostPins);

        return pins;
    }

    /**
     * Gets the last ARC (Attestation Response Code) code.
     *
     * Always resolves with a string (ARC or empty string).
     * NOTE: You MUST only call this method upon succesfull attestation completion. Any networking
     * errors returned from the service layer will not return a meaningful ARC code if the method is called!!!
     * @return String ARC from last attestation request or empty string if network unavailable
     */
    public static String getLastARC() {
        if (!isApproovEnabled()) {
            Log.i(TAG, "ApproovService: ARC code unavailable (SDK not initialized)");
            return "";
        }
        // Get the dynamic pins from Approov
        Map<String, List<String>> approovPins = Approov.getPins("public-key-sha256");
        if (approovPins == null || approovPins.isEmpty()) {
            Log.e(TAG, "ApproovService: no host pinning information available");
            return "";
        }
        // The approovPins contains a map of hostnames to pin strings. Skip '*' and use another hostname if available.
        String hostname = null;
        for (String key : approovPins.keySet()) {
            if (!"*".equals(key)) {
                hostname = key;
                break;
            }
        }
        if (hostname != null) {
            try {
                Approov.TokenFetchResult result = Approov.fetchApproovTokenAndWait(hostname);
                if (result.getToken() != null && !result.getToken().isEmpty()) {
                    String arc = result.getARC();
                    if (arc != null) {
                        return arc;
                    }
                }
                Log.i(TAG, "ApproovService: ARC code unavailable");
                return "";
            } catch (Exception e) {
                Log.e(TAG, "ApproovService: error fetching ARC", e);
                return "";
            }
        } else {
            Log.i(TAG, "ApproovService: ARC code unavailable");
            return "";
        }
    }

    /**
     * Sets an install attributes token to be sent to the server and associated with this particular
     * app installation for future Approov token fetches. The token must be signed, within its
     * expiry time and bound to the correct device ID for it to be accepted by the server.
     * Calling this method ensures that the next call to fetch an Approov
     * token will not use a cached version, so that this information can be transmitted to the server.
     *
     * @param attrs is the signed JWT holding the new install attributes
     * @return void
     * @throws ApproovException if the attrs parameter is invalid or the SDK is not initialized
     */
    public static void setInstallAttrsInToken(String attrs) throws ApproovException {
        if (!isApproovEnabled()) {
            throw new ApproovException("setInstallAttrsInToken: SDK not initialized");
        }
        try {
            Approov.setInstallAttrsInToken(attrs);
            Log.d(TAG, "setInstallAttrsInToken");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalArgument: " + e.getMessage());
            throw new ApproovException("setInstallAttrsInToken: " + e.getMessage());
        } catch (IllegalStateException e) {
            Log.e(TAG, "setInstallAttrsInToken failed with IllegalState: " + e.getMessage());
            throw new ApproovException("setInstallAttrsInToken: " + e.getMessage());
        }
    }

}


