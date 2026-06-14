//
// MIT License
//
// Copyright (c) 2016-present, Approov Ltd.
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files
// (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge,
// publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
// subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
// ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
// THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

package io.approov.service.grpc;

import com.criticalblue.approovsdk.Approov;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.grpc.Metadata;

/**
 * ApproovServiceMutator provides an interface for modifying the behavior of
 * the ApproovService class by overriding the default implementations of the
 * defined callbacks. Opportunities to modify behavior are offered at key
 * points in the service and attestation flows.
 *
 * The interface provides default implementations for all methods, so
 * implementing classes can choose to override only the methods they are
 * interested in. The default implementations provide standard fail-closed
 * behavior that is suitable for most use cases.
 *
 * This is the gRPC form of the mutator: gRPC requests carry no URL query string
 * or accessible body at the interceptor layer, so it omits query-parameter
 * substitution and operates on the request hostname, the (optional) RPC path,
 * and the gRPC {@link io.grpc.Metadata} headers.
 */
public interface ApproovServiceMutator {
    /**
     * Default mutator that provides standard fail-closed behavior with no changes.
     */
    ApproovServiceMutator DEFAULT = new ApproovServiceMutator() {
        @Override
        public String toString() {
            return "ApproovServiceMutator.DEFAULT";
        }
    };

    /**
     * ApproovRequest stores information about a gRPC request being processed for Approov.
     * gRPC requests carry only a target hostname, an (optional) RPC path of the form
     * "/package.Service/Method" and the request {@link io.grpc.Metadata} headers. The
     * headers are mutated in place by the interceptor.
     */
    final class ApproovRequest {
        private final String hostname;
        private final String path;
        private final Metadata headers;

        /**
         * Constructs an ApproovRequest.
         *
         * @param hostname the target hostname
         * @param path the RPC path ("/package.Service/Method") or null if unavailable
         * @param headers the gRPC metadata headers (mutated in place)
         */
        public ApproovRequest(String hostname, String path, Metadata headers) {
            this.hostname = hostname;
            this.path = path;
            this.headers = headers;
        }

        public String getHostname() {
            return hostname;
        }

        public String getPath() {
            return path;
        }

        public Metadata getHeaders() {
            return headers;
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.precheck() operation.
     *
     * @param approovResults the TokenFetchResult obtained by ApproovService.precheck()
     * @throws ApproovException The implementation can either return, taking no
     *                          action, or throw an ApproovException encoding the cause of the failure.
     */
    default void handlePrecheckResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "precheck: " + status + ": " + approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                        approovResults.getARC(), approovResults.getRejectionReasons());
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("precheck: " + status);
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovException("precheck: " + status);
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchToken() operation.
     *
     * @param approovResults the TokenFetchResult obtained by ApproovService.fetchToken()
     * @throws ApproovException The implementation can either return, taking no
     *                          action, or throw an ApproovException encoding the cause of the failure.
     */
    default void handleFetchTokenResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                break;
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("fetchToken: " + status);
            default:
                throw new ApproovException("fetchToken: " + status);
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchSecureString() operation.
     *
     * @param approovResults the TokenFetchResult obtained by ApproovService.fetchSecureString()
     * @param operation      the operation type ("lookup" or "definition")
     * @param key            the secure string key
     * @throws ApproovException The implementation can either return, taking no
     *                          action, or throw an ApproovException encoding the cause of the failure.
     */
    default void handleFetchSecureStringResult(Approov.TokenFetchResult approovResults, String operation, String key)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException("fetchSecureString " + operation + " for " + key + ": "
                        + status + ": " + approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                        approovResults.getARC(), approovResults.getRejectionReasons());
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException(
                        "fetchSecureString " + operation + " for " + key + ": " + status);
            case SUCCESS:
            case UNKNOWN_KEY:
                break;
            default:
                throw new ApproovException(
                        "fetchSecureString " + operation + " for " + key + ": " + status);
        }
    }

    /**
     * Decides how to handle the token fetch result from an
     * ApproovService.fetchCustomJWT() operation.
     *
     * @param approovResults the TokenFetchResult obtained by ApproovService.fetchCustomJWT()
     * @throws ApproovException The implementation can either return, taking no
     *                          action, or throw an ApproovException encoding the cause of the failure.
     */
    default void handleFetchCustomJWTResult(Approov.TokenFetchResult approovResults) throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case REJECTED:
                throw new ApproovRejectionException(
                        "fetchCustomJWT: " + status + ": " + approovResults.getARC() + " "
                                + approovResults.getRejectionReasons(),
                        approovResults.getARC(), approovResults.getRejectionReasons());
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("fetchCustomJWT: " + status);
            case SUCCESS:
                break;
            default:
                throw new ApproovException("fetchCustomJWT: " + status);
        }
    }

    /**
     * Decides whether a request should be processed in the interceptor or not.
     * Called at the start of the ApproovService interceptor processing. The default
     * implementation matches the request against the configured exclusion URL regexes.
     *
     * gRPC has no natural request URL at the interceptor layer, so exclusion regexes are
     * matched against a reconstructed URL string: if a path is present this is
     * "https://&lt;hostname&gt;&lt;path&gt;", otherwise the bare hostname is used.
     *
     * @param request the request being processed
     * @return true if the request should be processed by the Approov interceptor,
     *         false if it should be issued unchanged
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the cause of the failure
     */
    default boolean handleInterceptorShouldProcessRequest(ApproovRequest request) throws ApproovException {
        if (request == null)
            throw new ApproovException(
                    "handleInterceptorShouldProcessRequest method was passed a request that is null!");
        String urlString;
        String path = request.getPath();
        if (path != null && !path.isEmpty()) {
            urlString = "https://" + request.getHostname() + path;
        } else {
            urlString = request.getHostname();
        }
        for (Pattern pattern : ApproovService.getExclusionURLRegexs().values()) {
            Matcher matcher = pattern.matcher(urlString);
            if (matcher.find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decides how to handle the token fetch result from a call to
     * Approov.fetchApproovTokenAndWait() from within the interceptor.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param url            the URL string for which the token was requested
     * @return true if the token should be added to the request, false if the
     *         request should proceed even though no token was obtained from the fetch
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the cause of the failure
     */
    default boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                return true;
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                // a networking failure that the user may be able to retry. If the fallback status
                // is being injected then we still add the (status) header rather than aborting.
                if (ApproovService.getUseApproovStatusIfNoToken())
                    return true;
                throw new ApproovNetworkException("Approov token fetch for " + url + ": " + status);
            case NO_APPROOV_SERVICE:
            case UNKNOWN_URL:
            case UNPROTECTED_URL:
                // forward the request unmodified for URLs that are not protected by Approov
                return false;
            default:
                throw new ApproovException("Approov token fetch for " + url + ": " + status);
        }
    }

    /**
     * Decides how to handle the token fetch result while substituting headers from
     * within the interceptor. Called once per header being processed for substitution.
     *
     * @param approovResults the TokenFetchResult from Approov
     * @param header         the header being substituted
     * @return true if substitution should proceed, false if it should be skipped
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the cause of the failure
     */
    default boolean handleInterceptorHeaderSubstitutionResult(Approov.TokenFetchResult approovResults, String header)
            throws ApproovException {
        Approov.TokenFetchStatus status = approovResults.getStatus();
        switch (status) {
            case SUCCESS:
                return true;
            case REJECTED:
                throw new ApproovRejectionException("Header substitution for " + header + ": " + status
                        + ": " + approovResults.getARC() + " " + approovResults.getRejectionReasons(),
                        approovResults.getARC(), approovResults.getRejectionReasons());
            case NO_NETWORK:
            case POOR_NETWORK:
            case MITM_DETECTED:
                throw new ApproovNetworkException("Header substitution for " + header + ": " + status);
            case UNKNOWN_KEY:
                return false;
            default:
                throw new ApproovException("Header substitution for " + header + ": " + status);
        }
    }

    /**
     * Called after Approov has processed a network request, allowing further
     * modifications (for example to add message signature headers).
     *
     * @param request the processed request
     * @param changes the mutations applied to the request by Approov
     * @return the final request to use to complete the Approov interceptor step
     * @throws ApproovException The implementation can either return as described
     *                          above or throw an ApproovException encoding the cause of the failure
     */
    default ApproovRequest handleInterceptorProcessedRequest(ApproovRequest request, ApproovRequestMutations changes)
            throws ApproovException {
        // No further changes to the request are required
        return request;
    }

    /**
     * Decides whether certificate pinning should be applied to a hostname or not.
     *
     * @param hostname the hostname being processed
     * @return true if pinning should be applied, false to skip it
     */
    default boolean handlePinningShouldProcessRequest(String hostname) {
        // By default do not skip pinning for any requests
        return true;
    }
}
