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

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.approov.util.http.sfv.ByteSequenceItem;
import io.approov.util.http.sfv.Dictionary;
import io.approov.util.sig.ComponentProvider;
import io.approov.util.sig.SignatureBaseBuilder;
import io.approov.util.sig.SignatureParameters;
import io.grpc.Metadata;

/**
 * Provides a base implementation of HTTP message signing (RFC 9421) for Approov when using gRPC.
 * It installs as an {@link ApproovServiceMutator} and adds {@code Signature} / {@code Signature-Input}
 * headers to the gRPC request metadata once an Approov token has been added.
 *
 * Note: gRPC requests carry no buffered body at the interceptor layer, so body digests
 * ({@code Content-Digest}) are not produced for gRPC. Signing covers the derived components
 * ({@code @method}, {@code @target-uri}, ...), the Approov token / trace-ID headers, and any
 * configured optional headers.
 *
 * Message signing is <b>fail-open</b> (TESTING_REQUIREMENTS.md §5): the only failures that fail
 * <b>closed</b> (propagate and abort the request) are an unsupported signing algorithm and a body
 * digest configured as required (which is not applicable to gRPC as there is no accessible body).
 * All other signing failures - the SDK being unable to provide a signature, a base64 decode failure
 * or an ASN.1/DER decode failure - proceed unsigned and log at error level.
 */
public class ApproovDefaultMessageSigning implements ApproovServiceMutator {
    // logging tag
    private static final String TAG = "ApproovMsgSign";

    /**
     * Constant for the SHA-256 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA256 = "sha-256";

    /**
     * Constant for the SHA-512 digest algorithm (used for body digests).
     */
    public static final String DIGEST_SHA512 = "sha-512";

    /**
     * Constant for the ECDSA P-256 with SHA-256 algorithm (used when signing with install private key).
     */
    public static final String ALG_ES256 = "ecdsa-p256-sha256";

    /**
     * Constant for the HMAC with SHA-256 algorithm (used when signing with the account signing key).
     */
    public static final String ALG_HS256 = "hmac-sha256";

    /**
     * The default factory for generating signature parameters.
     */
    protected SignatureParametersFactory defaultFactory;

    /**
     * A map of host-specific factories for generating signature parameters.
     */
    protected final Map<String, SignatureParametersFactory> hostFactories;

    /**
     * Constructs an instance of {@code ApproovDefaultMessageSigning}.
     */
    public ApproovDefaultMessageSigning() {
        hostFactories = new HashMap<>();
    }

    @Override
    public String toString() {
        return "ApproovDefaultMessageSigning";
    }

    /**
     * Sets the default factory for generating signature parameters.
     *
     * @param factory The factory to set as the default.
     * @return The current instance for method chaining.
     */
    public ApproovDefaultMessageSigning setDefaultFactory(SignatureParametersFactory factory) {
        this.defaultFactory = factory;
        return this;
    }

    /**
     * Associates a specific host with a factory for generating signature parameters.
     *
     * @param hostName The host name.
     * @param factory The factory to associate with the host.
     * @return The current instance for method chaining.
     */
    public ApproovDefaultMessageSigning putHostFactory(String hostName, SignatureParametersFactory factory) {
        this.hostFactories.put(hostName, factory);
        return this;
    }

    /**
     * Builds the signature parameters for a given request.
     *
     * @param provider The component provider for the request.
     * @param changes The request mutations to apply.
     * @return The generated {@link SignatureParameters}, or {@code null} if no factory is available.
     */
    protected SignatureParameters buildSignatureParameters(ApproovGRPCComponentProvider provider,
            ApproovRequestMutations changes) {
        SignatureParametersFactory factory = hostFactories.get(provider.getAuthority());
        if (factory == null) {
            factory = defaultFactory;
            if (factory == null) {
                return null;
            }
        }
        return factory.buildSignatureParameters(provider, changes);
    }

    /**
     * Decodes a base64-encoded signature value, returning null on a decode failure (fail-open).
     *
     * @param base64 The signature bytes encoded as base64.
     * @return The decoded bytes, or null if the value could not be decoded.
     */
    protected byte[] decodeBase64(String base64) {
        try {
            return Base64.decode(base64, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Adds message signature headers to requests that have passed through the Approov interceptor.
     * The request metadata is only modified to include message signature headers if an Approov token
     * has been added to the request and there is a defined SignatureParameters factory for the request.
     *
     * @param request The Approov gRPC request (its metadata is mutated in place).
     * @param changes The request mutations that were applied by the Approov interceptor.
     * @return The processed request.
     * @throws ApproovException For the fail-closed cases only (unsupported algorithm or a required
     *                          body digest that cannot be generated). All other signing failures are
     *                          fail-open (proceed unsigned + log at error level).
     */
    @Override
    public ApproovRequest handleInterceptorProcessedRequest(ApproovRequest request, ApproovRequestMutations changes)
            throws ApproovException {
        if (changes == null || changes.getTokenHeaderKey() == null) {
            // the request doesn't have an Approov token, so we don't need to sign it
            return request;
        }
        // generate and add a message signature
        ApproovGRPCComponentProvider provider = new ApproovGRPCComponentProvider(request);
        SignatureParameters params;
        try {
            params = buildSignatureParameters(provider, changes);
        } catch (IllegalStateException e) {
            throw new ApproovException("Failed to build signature parameters: " + e.getMessage(), e);
        }
        if (params == null) {
            // No signature to be added to the request; return the original request.
            return request;
        }

        // Apply the params to get the message
        SignatureBaseBuilder baseBuilder = new SignatureBaseBuilder(params, provider);
        String message;
        try {
            message = baseBuilder.createSignatureBase();
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to create signature base, skipping signing: " + e.getMessage());
            return request;
        }
        // WARNING never log the message as it contains an Approov token which provides access to your API.

        // Generate the signature
        String sigId;
        byte[] signature;
        switch (params.getAlg()) {
            case ALG_ES256: {
                sigId = "install";
                // Fail-open: if the SDK cannot provide a usable install signature (none available, or a
                // value that cannot be base64-decoded) we proceed unsigned and log at error level.
                String base64 = ApproovService.getInstallMessageSignature(message);
                if (base64 == null || base64.isEmpty()) {
                    Log.e(TAG, "install message signature unavailable, skipping signing");
                    return request;
                }
                byte[] derSignature = decodeBase64(base64);
                if (derSignature == null) {
                    Log.e(TAG, "failed to base64-decode install signature, skipping signing");
                    return request;
                }
                // Decode the signature from ASN.1 DER format. A malformed signature is also fail-open.
                try {
                    signature = decodeASN1DERES256Signature(derSignature);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "failed to decode ASN.1 DER install signature, skipping signing: " + e.getMessage());
                    return request;
                }
                break;
            }
            case ALG_HS256: {
                sigId = "account";
                // Fail-open: if the SDK cannot provide a usable account signature (none available, e.g.
                // no mksid yet, or a value that cannot be base64-decoded) we proceed unsigned and log.
                String base64 = ApproovService.getAccountMessageSignature(message);
                if (base64 == null || base64.isEmpty()) {
                    Log.e(TAG, "account message signature unavailable, skipping signing");
                    return request;
                }
                signature = decodeBase64(base64);
                if (signature == null) {
                    Log.e(TAG, "failed to base64-decode account signature, skipping signing");
                    return request;
                }
                break;
            }
            default:
                // Unsupported algorithm is a configuration error and fails closed.
                throw new ApproovException("Unsupported algorithm identifier: " + params.getAlg());
        }

        // Calculate the signature and signature-input header values
        String sigHeader = Dictionary.valueOf(singleEntryMap(
                sigId, ByteSequenceItem.valueOf(signature))).serialize();
        String sigInputHeader = Dictionary.valueOf(singleEntryMap(
                sigId, params.toComponentValue())).serialize();

        // Add the headers to the request metadata. Remove any existing values first so that
        // re-processing an already-signed request does not emit duplicate header lines.
        Metadata headers = provider.getRequest().getHeaders();
        replaceMetadata(headers, "Signature", sigHeader);
        replaceMetadata(headers, "Signature-Input", sigInputHeader);
        headers.removeAll(Metadata.Key.of("Signature-Base-Digest", Metadata.ASCII_STRING_MARSHALLER));

        if (params.isDebugMode()) {
            try {
                MessageDigest digestBuilder = MessageDigest.getInstance("SHA-256");
                digestBuilder.reset();
                byte[] digest = digestBuilder.digest(message.getBytes(StandardCharsets.UTF_8));
                String digestHeader = Dictionary.valueOf(singleEntryMap(
                        DIGEST_SHA256, ByteSequenceItem.valueOf(digest))).serialize();
                replaceMetadata(headers, "Signature-Base-Digest", digestHeader);
            } catch (NoSuchAlgorithmException e) {
                Log.d(TAG, "Failed to get digest algorithm - no debug entry " + e);
            }
        }

        // WARNING never log the full request as it contains an Approov token which provides access to your API
        return provider.getRequest();
    }

    /**
     * Helper to build a single-entry map for Android compatibility (avoiding Java 9 Map.of).
     */
    private static <K, V> Map<K, V> singleEntryMap(K key, V value) {
        Map<K, V> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Replaces (removing any existing values for) a single ASCII header on the given metadata.
     */
    private static void replaceMetadata(Metadata headers, String name, String value) {
        Metadata.Key<String> key = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
        headers.removeAll(key);
        headers.put(key, value);
    }

    /**
     * Decodes an ASN.1 DER encoded ES256 signature into the "raw" r||s signature format (32 + 32
     * bytes). This is a pure byte parser - it intentionally avoids any third-party ASN.1 dependency.
     *
     * @param signature The ASN.1 DER encoded signature bytes.
     * @return The 64 byte raw signature (r concatenated with s).
     * @throws IllegalArgumentException if the input is not a valid ASN.1 DER ES256 signature.
     */
    static byte[] decodeASN1DERES256Signature(byte[] signature) {
        int offset = 0;

        // Ensure signature has at least 2 bytes (tag and length)
        if (signature.length < 2) {
            throw new IllegalArgumentException("ASN.1 DER signature too short");
        }

        // Ensure the signature starts with a valid ASN.1 sequence
        if ((signature[offset] & 0xFF) != 0x30) {
            throw new IllegalArgumentException("Invalid ASN.1 DER sequence");
        }
        offset += 1;

        // Read the total length of the sequence
        int sequenceLength = signature[offset] & 0xFF;
        offset += 1;

        if (sequenceLength != signature.length - 2) {
            throw new IllegalArgumentException("Invalid ASN.1 DER sequence length");
        }

        // Ensure there are at least 2 more bytes for r's tag and length
        if (offset + 2 > signature.length) {
            throw new IllegalArgumentException("Truncated ASN.1 DER signature reading r");
        }

        // Decode the first integer (r)
        if ((signature[offset] & 0xFF) != 0x02) {
            throw new IllegalArgumentException("Invalid ASN.1 DER integer for r");
        }
        offset += 1;

        int rLength = signature[offset] & 0xFF;
        offset += 1;

        if (offset + rLength > signature.length) {
            throw new IllegalArgumentException("Truncated ASN.1 DER signature reading r value");
        }
        byte[] rBytes = Arrays.copyOfRange(signature, offset, offset + rLength);
        offset += rLength;

        // Ensure there are at least 2 more bytes for s's tag and length
        if (offset + 2 > signature.length) {
            throw new IllegalArgumentException("Truncated ASN.1 DER signature reading s");
        }

        // Decode the second integer (s)
        if ((signature[offset] & 0xFF) != 0x02) {
            throw new IllegalArgumentException("Invalid ASN.1 DER integer for s");
        }
        offset += 1;

        int sLength = signature[offset] & 0xFF;
        offset += 1;

        if (offset + sLength > signature.length) {
            throw new IllegalArgumentException("Truncated ASN.1 DER signature reading s value");
        }
        byte[] sBytes = Arrays.copyOfRange(signature, offset, offset + sLength);
        offset += sLength;

        // Ensure the entire signature has been processed
        if (offset != signature.length) {
            throw new IllegalArgumentException("Extra data in ASN.1 DER signature");
        }

        byte[] r = to32ByteArray(rBytes);
        byte[] s = to32ByteArray(sBytes);
        byte[] raw = new byte[r.length + s.length];
        System.arraycopy(r, 0, raw, 0, r.length);
        System.arraycopy(s, 0, raw, r.length, s.length);
        return raw;
    }

    /**
     * Converts one part of an ASN.1 DER encoded ES256 signature to a byte array of exactly 32 bytes.
     *
     * @param bytes The raw integer bytes.
     * @return A byte array of length 32 containing the raw bytes of the signature part.
     * @throws IllegalArgumentException if the input cannot be represented as 32 bytes.
     */
    private static byte[] to32ByteArray(byte[] bytes) {
        byte[] bytes32;
        if (bytes.length < 32) {
            bytes32 = new byte[32];
            System.arraycopy(bytes, 0, bytes32, 32 - bytes.length, bytes.length);
        } else if (bytes.length == 32) {
            bytes32 = bytes;
        } else if (bytes.length == 33 && bytes[0] == 0) {
            bytes32 = new byte[32];
            System.arraycopy(bytes, 1, bytes32, 0, 32);
        } else {
            throw new IllegalArgumentException("Not an ASN.1 DER ES256 signature part");
        }
        return bytes32;
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with predefined settings.
     *
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory() {
        return generateDefaultSignatureParametersFactory(null);
    }

    /**
     * Generates a default {@link SignatureParametersFactory} with optional base parameters. The
     * default signs {@code @method} and {@code @target-uri}, the Approov token and trace-ID headers,
     * and the {@code Authorization} header when present. No body digest is configured (gRPC has no
     * accessible body at the interceptor layer).
     *
     * @param baseParametersOverride The base parameters to override, or {@code null} to use defaults.
     * @return A new instance of {@link SignatureParametersFactory}.
     */
    public static SignatureParametersFactory generateDefaultSignatureParametersFactory(
            SignatureParameters baseParametersOverride) {
        // default expiry seconds - must encompass worst case request retry time and clock skew
        long defaultExpiresLifetime = 15;
        SignatureParameters baseParameters;
        if (baseParametersOverride != null) {
            baseParameters = baseParametersOverride;
        } else {
            baseParameters = new SignatureParameters()
                    .addComponentIdentifier(ComponentProvider.DC_METHOD)
                    .addComponentIdentifier(ComponentProvider.DC_TARGET_URI);
        }
        // Note: no body digest is configured for gRPC.
        return new SignatureParametersFactory()
                .setBaseParameters(baseParameters)
                .setUseInstallMessageSigning()
                .setAddCreated(true)
                .setExpiresLifetime(defaultExpiresLifetime)
                .setAddApproovTokenHeader(true)
                .setAddApproovTraceIDHeader(true)
                .addOptionalHeaders("Authorization");
    }

    /**
     * Factory class for creating per-request {@link SignatureParameters} with configurable settings.
     */
    public static class SignatureParametersFactory {
        protected SignatureParameters baseParameters;
        protected String bodyDigestAlgorithm;
        protected boolean bodyDigestRequired;
        protected boolean useAccountMessageSigning;
        protected boolean addCreated;
        protected long expiresLifetime;
        protected boolean addApproovTokenHeader;
        protected boolean addApproovTraceIDHeader;
        protected List<String> optionalHeaders;

        public SignatureParametersFactory setBaseParameters(SignatureParameters baseParameters) {
            this.baseParameters = baseParameters;
            return this;
        }

        /**
         * Configures the body digest settings. NOTE: gRPC has no buffered body at the interceptor
         * layer, so a body digest can never actually be generated; configuring one with
         * {@code required = true} will cause signing to fail closed.
         *
         * @param bodyDigestAlgorithm The digest algorithm to use, or {@code null} to disable.
         * @param required Whether the body digest is required.
         * @return The current instance for method chaining.
         * @throws IllegalArgumentException If an unsupported algorithm is specified.
         */
        public SignatureParametersFactory setBodyDigestConfig(String bodyDigestAlgorithm, boolean required) {
            if (bodyDigestAlgorithm == null) {
                required = false;
            } else if (!bodyDigestAlgorithm.equals(DIGEST_SHA256) && !bodyDigestAlgorithm.equals(DIGEST_SHA512)) {
                throw new IllegalArgumentException("Unsupported body digest algorithm: " + bodyDigestAlgorithm);
            }
            this.bodyDigestAlgorithm = bodyDigestAlgorithm;
            this.bodyDigestRequired = required;
            return this;
        }

        public SignatureParametersFactory setUseInstallMessageSigning() {
            this.useAccountMessageSigning = false;
            return this;
        }

        public SignatureParametersFactory setUseAccountMessageSigning() {
            this.useAccountMessageSigning = true;
            return this;
        }

        public SignatureParametersFactory setAddCreated(boolean addCreated) {
            this.addCreated = addCreated;
            return this;
        }

        public SignatureParametersFactory setExpiresLifetime(long expiresLifetime) {
            this.expiresLifetime = expiresLifetime;
            return this;
        }

        public SignatureParametersFactory setAddApproovTokenHeader(boolean addApproovTokenHeader) {
            this.addApproovTokenHeader = addApproovTokenHeader;
            return this;
        }

        public SignatureParametersFactory setAddApproovTraceIDHeader(boolean addApproovTraceIDHeader) {
            this.addApproovTraceIDHeader = addApproovTraceIDHeader;
            return this;
        }

        public SignatureParametersFactory addOptionalHeaders(String... headers) {
            if (this.optionalHeaders == null) {
                this.optionalHeaders = new ArrayList<>(Arrays.asList(headers));
            } else {
                this.optionalHeaders.addAll(Arrays.asList(headers));
            }
            return this;
        }

        /**
         * gRPC requests carry no buffered body at the interceptor layer, so a Content-Digest cannot
         * be generated. Always returns false; a required digest therefore fails closed.
         */
        protected boolean generateBodyDigest() {
            return false;
        }

        /**
         * Builds the signature parameters for a given request.
         *
         * @param provider The component provider for the request.
         * @param changes The request mutations to apply.
         * @return The generated {@link SignatureParameters}.
         * @throws IllegalStateException If a required body digest cannot be generated.
         */
        protected SignatureParameters buildSignatureParameters(ApproovGRPCComponentProvider provider,
                ApproovRequestMutations changes) {
            SignatureParameters requestParameters = new SignatureParameters(baseParameters);
            if (useAccountMessageSigning) {
                requestParameters.setAlg(ALG_HS256);
            } else {
                requestParameters.setAlg(ALG_ES256);
            }
            if (addCreated || expiresLifetime > 0) {
                long currentTime = System.currentTimeMillis() / 1000;
                if (addCreated) {
                    requestParameters.setCreated(currentTime);
                }
                if (expiresLifetime > 0) {
                    requestParameters.setExpires(currentTime + expiresLifetime);
                }
            }
            if (addApproovTokenHeader && changes.getTokenHeaderKey() != null) {
                requestParameters.addComponentIdentifier(changes.getTokenHeaderKey());
            }
            if (addApproovTraceIDHeader && changes.getTraceIDHeaderKey() != null) {
                requestParameters.addComponentIdentifier(changes.getTraceIDHeaderKey());
            }
            if (optionalHeaders != null) {
                for (String headerName : optionalHeaders) {
                    if (provider.hasField(headerName)) {
                        requestParameters.addComponentIdentifier(headerName);
                    }
                }
            }
            if (bodyDigestAlgorithm != null) {
                // gRPC has no accessible body, so a digest can never be generated.
                if (!generateBodyDigest() && bodyDigestRequired) {
                    throw new IllegalStateException("Failed to create required body digest");
                }
            }
            return requestParameters;
        }
    }

    /**
     * ApproovGRPCComponentProvider implements the {@link ComponentProvider} interface for gRPC
     * requests. gRPC requests are always {@code POST} over {@code https}; the authority is the target
     * hostname and the path (when available from the interceptor) is the RPC path
     * {@code /package.Service/Method}. There is no query and no accessible body. Fields are read from
     * the request metadata.
     */
    protected static final class ApproovGRPCComponentProvider implements ComponentProvider {
        private ApproovRequest request;

        ApproovGRPCComponentProvider(ApproovRequest request) {
            this.request = request;
        }

        public ApproovRequest getRequest() {
            return request;
        }

        public void setRequest(ApproovRequest request) {
            this.request = request;
        }

        @Override
        public String getMethod() {
            return "POST";
        }

        @Override
        public String getAuthority() {
            return request.getHostname();
        }

        @Override
        public String getScheme() {
            return "https";
        }

        @Override
        public String getTargetUri() {
            String path = request.getPath();
            return "https://" + request.getHostname() + (path == null ? "" : path);
        }

        @Override
        public String getRequestTarget() {
            String path = request.getPath();
            return path == null ? "" : path;
        }

        @Override
        public String getPath() {
            String path = request.getPath();
            return path == null ? "" : path;
        }

        @Override
        public String getQuery() {
            return "";
        }

        @Override
        public String getQueryParam(String name) {
            return null;
        }

        @Override
        public String getStatus() {
            throw new IllegalStateException("Only requests are supported");
        }

        @Override
        public boolean hasField(String name) {
            return request.getHeaders().get(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)) != null;
        }

        @Override
        public String getField(String name) {
            Iterable<String> values = request.getHeaders().getAll(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
            if (values == null) {
                return null;
            }
            List<String> list = new ArrayList<>();
            for (String value : values) {
                list.add(value);
            }
            if (list.isEmpty()) {
                return null;
            }
            return ComponentProvider.combineFieldValues(list);
        }

        @Override
        public boolean hasBody() {
            return false;
        }
    }
}
