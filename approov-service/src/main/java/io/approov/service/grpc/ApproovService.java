// ApproovService for integrating Approov into apps using GRPC.
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
import android.content.SharedPreferences;
import android.util.Log;

import com.criticalblue.approovsdk.Approov;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.grpc.Metadata;

// ApproovService provides a mediation layer to the Approov SDK itself
public class ApproovService {
    // logging tag
    private static final String TAG = "ApproovService";

    // keys for the Approov shared preferences
    private static final String APPROOV_CONFIG = "approov-config";
    private static final String APPROOV_PREFS = "approov-prefs";

    // default header that will be added to Approov enabled requests
    private static final String APPROOV_TOKEN_HEADER = "Approov-Token";

    // default  prefix to be added before the Approov token by default
    private static final String APPROOV_TOKEN_PREFIX = "";

    // true if the Approov SDK initialized okay
    private static boolean initialized = false;

    // context for handling preferences
    private static Context appContext;

    // header to be used to send Approov tokens
    private static String approovTokenHeader;

    // any prefix String to be added before the transmitted Approov token
    private static String approovTokenPrefix;

    // any header to be used for binding in Approov tokens or null if not set
    private static String bindingHeader;

    // Private constructor to prevent instantiation
    private ApproovService() {}

    /**
     * Initializes the Approov service.
     *
     * @param context the Application context
     * @param config the initial service config string
     */
    public static synchronized void initialize(Context context, String config) {
        // Initialize only once
        if (initialized) {
            Log.e(TAG, "Approov service already initialized");
            return;
        }

        // setup for creating clients
        appContext = context;
        approovTokenHeader = APPROOV_TOKEN_HEADER;
        approovTokenPrefix = APPROOV_TOKEN_PREFIX;
        bindingHeader = null;
    
        // initialize the Approov SDK
        String dynamicConfig = getApproovDynamicConfig();
        try {
            Approov.initialize(context, config, dynamicConfig, null);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Approov initialization failed: " + e.getMessage());
            return;
        }
        initialized = true;

        // if we didn't have a dynamic configuration (after the first launch on the app) then
        // we fetch the latest and write it to local storage now
        if (dynamicConfig == null)
            updateDynamicConfig();
    }

    /**
     * Prefetches an Approov token in the background. The placeholder domain "www.approov.io" is
     * simply used to initiate the fetch and does not need to be a valid API for the account. This
     * method can be used to lower the effective latency of a subsequent token fetch by starting
     * the operation earlier so the subsequent fetch may be able to use a cached token.
     */
    public static synchronized void prefetchApproovToken() {
        if (initialized)
            Approov.fetchApproovToken(new PrefetchCallbackHandler(), "www.approov.io");
    }

    /**
     * Writes the latest dynamic configuration that the Approov SDK has. This clears the cached
     * OkHttp client since the pins may have changed and therefore a client rebuild is required.
     */
    public static synchronized void updateDynamicConfig() {
        Log.i(TAG, "Approov dynamic configuration updated");
        putApproovDynamicConfig(Approov.fetchConfig());
    }

    /**
     * Stores an application's dynamic configuration string in non-volatile storage.
     *
     * The default implementation stores the string in shared preferences, and setting
     * the config string to null is equivalent to removing the config.
     *
     * @param config a configuration string
     */
    protected static void putApproovDynamicConfig(String config) {
        SharedPreferences prefs = appContext.getSharedPreferences(APPROOV_PREFS, 0);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(APPROOV_CONFIG, config);
        editor.apply();
    }

    /**
     * Returns the application's dynamic configuration string from non-volatile storage.
     *
     * The default implementation retrieves the string from shared preferences.
     *
     * @return config string, or null if not present
     */
    protected static String getApproovDynamicConfig() {
        SharedPreferences prefs = appContext.getSharedPreferences(APPROOV_PREFS, 0);
        return prefs.getString(APPROOV_CONFIG, null);
    }

    /**
     * Sets the header that the Approov token is added on, as well as an optional
     * prefix String (such as "Bearer "). By default the token is provided on
     * "Approov-Token" with no prefix.
     *
     * @param header is the header to place the Approov token on
     * @param prefix is any prefix String for the Approov token header
     */
    public static synchronized void setApproovHeader(String header, String prefix) {
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
     * Adds Approov token to the given headers. If a binding header has been specified then this should be available
     * within the passed headers. If it is not currently possible to fetch an Approov token (typically due to no or poor
     * network) then an exception is thrown and a later retry should be made.
     *
     * @param host the host for which to fetch the Approov token
     * @param headers the headers to which to add the Approov token
     */
    static void addApproov(String host, Metadata headers) throws IOException {
        // just return if we couldn't initialize the SDK
        if (!initialized) {
            Log.e(TAG, "Cannot add Approov due to initialization failure");
            return;
        }

        // Update the data hash based on any token binding header
        String bindingHeader = getBindingHeader();
        if (bindingHeader != null) {
            String headerValue = headers.get(Metadata.Key.of(bindingHeader, Metadata.ASCII_STRING_MARSHALLER));
            if (headerValue != null)
                Approov.setDataHashInToken(headerValue);
        }
        // Request an Approov token for the domain
        // Fetch Approov token synchronously - may not be allowed here, see async implementation below
        Approov.TokenFetchResult tokenFetchResult = Approov.fetchApproovTokenAndWait(host);

        // provide information about the obtained token or error (note "approov token -check" can
        // be used to check the validity of the token and if you use token annotations they
        // will appear here to determine why a request is being rejected)
        Log.i(ApproovService.TAG, "Approov Token for " + host + ": " + tokenFetchResult.getLoggableToken());

        // Update any dynamic configuration
        if (tokenFetchResult.isConfigChanged())
            ApproovService.updateDynamicConfig();

        if (tokenFetchResult.getStatus() == Approov.TokenFetchStatus.SUCCESS) {
            // Add the token to the metadata (i.e. headers)
            String token = tokenFetchResult.getToken();
            headers.put(Metadata.Key.of(ApproovService.getApproovHeader(), Metadata.ASCII_STRING_MARSHALLER),
                    ApproovService.getApproovPrefix() + token);
        } else if (tokenFetchResult.getStatus() != Approov.TokenFetchStatus.NO_APPROOV_SERVICE &&
                tokenFetchResult.getStatus() != Approov.TokenFetchStatus.UNKNOWN_URL &&
                tokenFetchResult.getStatus() != Approov.TokenFetchStatus.UNPROTECTED_URL) {
            // Fetching an Approov token has failed in such a way that there is no point in proceeding with the
            // request - generally a retry is needed, unless the error is permanent.
            String message = "Approov token fetch failed: " + tokenFetchResult.getStatus().toString();
            Log.i(ApproovService.TAG, message);
            throw new IOException(message);
        }
    }

    /**
     * Gets the list of pins for a URL domain (hostname only). If the returned list is empty then that indicates that a
     * connection to the host is not specifically pinned.
     *
     * @param hostname is the name of the host (domain) for which to get the pins
     * @return set of strings providing the pins, may be empty
     */
    static Set<String> getPins(String hostname) {
        // extract the set of valid pins for the hostname
        Set<String> hostPins = new HashSet<>();
        @SuppressWarnings("unchecked")
        Map<String, List<String>> pins = Approov.getPins("public-key-sha256");
        for (Map.Entry<String, List<String>> entry: pins.entrySet()) {
            if (entry.getKey().equals(hostname)) {
                hostPins.addAll(entry.getValue());
            }
        }
        return hostPins;
    }

}

/**
 * Callback handler for prefetching an Approov token. We simply log as we don't need the token
 * itself, as it will be returned as a cached value on a subsequent token fetch.
 */
final class PrefetchCallbackHandler implements Approov.TokenFetchCallback {
    // logging tag
    private static final String TAG = "ApproovPrefetch";

    @Override
    public void approovCallback(Approov.TokenFetchResult pResult) {
        if (pResult.getStatus() == Approov.TokenFetchStatus.UNKNOWN_URL)
            Log.i(TAG, "Approov prefetch success");
        else
            Log.i(TAG, "Approov prefetch failure: " + pResult.getStatus().toString());
    }
}
