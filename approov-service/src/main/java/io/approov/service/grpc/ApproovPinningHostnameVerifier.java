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

import android.util.Log;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import okio.ByteString;

/**
 * Performs pinning for use with GRPC. This implementation of HostnameVerifier is intended to enhance the
 * HostnameVerifier your TLS implementation normally uses. The HostnameVerifier passed into the constructor continues
 * to be executed when verify is called. The pinning is only applied if the usual HostnameVerifier first passes (so
 * this implementation can only be more secure). This pins to the SHA256 of the public key hash of any certificate in
 * the trust chain for the host (so technically this is public key rather than certificate pinning).
 * Note that this uses the current live Approov pins so is immediately updated if there is a configuration update to
 * the app.
 */
final class ApproovPinningHostnameVerifier implements HostnameVerifier {

    /** HostnameVerifier you would normally be using */
    private final HostnameVerifier delegate;

    /** Tag for log messages */
    private static final String TAG = "ApproovPinVerifier";

    /**
     * Construct a PinningHostnameVerifier which delegates the initial verify to a user defined HostnameVerifier before
     * applying pinning on top.
     *
     * @param delegate is the HostnameVerifier to apply before the custom pinning
     */
    public ApproovPinningHostnameVerifier(HostnameVerifier delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean verify(String hostname, SSLSession session) {
        // check the delegate function first and only proceed if it passes
        if (delegate.verify(hostname, session)) try {
            // ensure pins are refreshed eventually
            ApproovService.prefetch();

            // extract the set of valid pins for the hostname
            Set<String> hostPins = ApproovService.getPins(hostname);

            // if there are no pins then we accept any certificate
            if (hostPins.isEmpty())
                return true;

            // check to see if any of the pins are in the certificate chain
            for (Certificate cert: session.getPeerCertificates()) {
                if (cert instanceof X509Certificate) {
                    X509Certificate x509Cert = (X509Certificate) cert;
                    ByteString digest = ByteString.of(x509Cert.getPublicKey().getEncoded()).sha256();
                    String hash = digest.base64();
                    if (hostPins.contains(hash))
                        return true;
                }
                else
                    Log.e(TAG, "Certificate not X.509");
            }

            // the connection is rejected
            Log.w(TAG, "Pinning rejection for " + hostname);
            return false;
        } catch (SSLException e) {
            Log.e(TAG, "Delegate Exception");
            throw new RuntimeException(e);
        }
        return false;
    }
}
