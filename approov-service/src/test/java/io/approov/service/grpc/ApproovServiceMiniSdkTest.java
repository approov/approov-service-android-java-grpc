package io.approov.service.grpc;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.criticalblue.minisdk.testing.AttesterProxyController;
import com.criticalblue.approovsdk.Approov;
import io.grpc.Metadata;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Integration tests for the ApproovService gRPC service layer.
 * This is a testing requirement and has no production dependency at all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class ApproovServiceMiniSdkTest {
    private final String validInitialConfig = "#cb-ivol#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
    private final String targetHost = "grpc.example.com";
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        AttesterProxyController.reset();
        ApproovService.resetForTesting();
        ApproovService.initialize(context, validInitialConfig);
    }

    @After
    public void tearDown() {
        AttesterProxyController.reset();
        ApproovService.resetForTesting();
    }

    private String scenarioJson(String caseName, String body) {
        return "{\n  \"activeCase\": \"" + caseName + "\",\n  \"cases\": {\n    \"" + caseName + "\": {\n      " + body + "\n    }\n  }\n}";
    }

    private String uniqueCaseName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().toLowerCase();
    }

    private JSONObject decodeJWTBody(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }
        String body = parts[1];
        body = body.replace('-', '+').replace('_', '/');
        int remainder = body.length() % 4;
        if (remainder > 0) {
            body = body + "====".substring(remainder);
        }
        byte[] decodedBytes = Base64.getDecoder().decode(body);
        return new JSONObject(new String(decodedBytes, StandardCharsets.UTF_8));
    }

    // ==================================================================================
    // SECTION 1: Initialization
    // ==================================================================================

    @Test
    public void testInitializeIgnoresSameConfigAndRejectsDifferentConfig() {
        // §1 Same Config Re-initialization: forwarded to the SDK which returns false (already
        // initialized); the service layer treats this as success and remains fully initialized.
        ApproovService.initialize(context, validInitialConfig);
        assertTrue(ApproovService.isInitialized());
        assertTrue(ApproovService.isApproovEnabled());

        // §1 Different Non-empty Config Re-initialization: forwarded to the SDK which throws an
        // IllegalStateException. The native rejection is surfaced and the service-layer state is
        // left completely unchanged (still protected with the original config).
        String differentConfig = "#cb-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
        assertThrows(IllegalStateException.class, () ->
            ApproovService.initialize(context, differentConfig));
        assertTrue(ApproovService.isInitialized());
        assertTrue(ApproovService.isApproovEnabled());
    }

    @Test
    public void testInitializeWithEmptyConfigBypassesTokenInjection() throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("target-host"),
            "\"protectedDomains\": [\"" + targetHost + "\"]"));
        ApproovService.resetForTesting();
        ApproovService.initialize(context, "");

        assertTrue(ApproovService.isInitialized());
        assertFalse(ApproovService.isApproovEnabled());

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);

        assertNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    public void testInitializeWithEmptyConfigCanLaterEnableApproov() throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("target-host"),
            "\"protectedDomains\": [\"" + targetHost + "\"]"));
        ApproovService.resetForTesting();
        ApproovService.initialize(context, "");

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);
        assertNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));

        assertFalse(ApproovService.isApproovEnabled());

        ApproovService.initialize(context, validInitialConfig);
        assertTrue(ApproovService.isApproovEnabled());

        Metadata protectedHeaders = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", protectedHeaders);
        assertNotNull(protectedHeaders.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
    }

    // ==================================================================================
    // SECTION 2: Request Processing & Token Behaviors
    // ==================================================================================

    @Test
    public void testPrecheckTreatsUnknownKeyAsSuccess() throws Exception {
        ApproovService.precheck();
    }

    @Test
    public void testGetDeviceIDReturnsMiniSDKDeviceID() throws Exception {
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", ApproovService.getDeviceID());
    }

    @Test
    public void testUpdateRequestAddsTokenAndSubstitutions() throws Exception {
        String scenario = scenarioJson(uniqueCaseName("substitutions"),
            "\"protectedDomains\": [\"" + targetHost + "\"]," +
            "\"initialSecureStrings\": {" +
            "  \"header-key\": \"header-secret\"" +
            "}"
        );
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenario);
        ApproovService.resetForTesting();
        ApproovService.initialize(context, validInitialConfig);

        ApproovService.setBindingHeader("authorization");
        ApproovService.addSubstitutionHeader("api-key", null);

        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer oauth-token");
        headers.put(Metadata.Key.of("api-key", Metadata.ASCII_STRING_MARSHALLER), "header-key");

        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);

        String token = headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER));
        assertNotNull(token);
        assertEquals("header-secret", headers.get(Metadata.Key.of("api-key", Metadata.ASCII_STRING_MARSHALLER)));

        JSONObject payload = decodeJWTBody(token);
        assertEquals("daIvmEWBA2gvZny7a/RC/w==", payload.getString("did"));
    }

    @Test
    public void testFetchTokenReturnsSignedTokenWithExpectedClaims() throws Exception {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("target-host"),
            "\"protectedDomains\": [\"" + targetHost + "\"]"));
        ApproovService.resetForTesting();
        ApproovService.initialize(context, validInitialConfig);

        String token = ApproovService.fetchToken(targetHost);
        JSONObject payload = decodeJWTBody(token);

        assertEquals("daIvmEWBA2gvZny7a/RC/w==", payload.getString("did"));
    }

    // ==================================================================================
    // SECTION 6: Secure Strings
    // ==================================================================================

    @Test
    public void testFetchSecureStringReturnsNilForUnknownKey() throws Exception {
        String secureString = ApproovService.fetchSecureString("missing-key", null);
        assertNull(secureString);
    }

    // ==================================================================================
    // SECTION 2/3: Exclusion, Trace ID, Token Fallback, Custom Mutator
    // ==================================================================================

    private void reinitWithProtectedHost() {
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("target-host"),
            "\"protectedDomains\": [\"" + targetHost + "\"]"));
        ApproovService.resetForTesting();
        ApproovService.initialize(context, validInitialConfig);
    }

    @Test
    public void testExcludedUrlIsNotMutated() throws Exception {
        reinitWithProtectedHost();
        // exclude the reconstructed "https://<host><path>" URL
        ApproovService.addExclusionURLRegex("https://" + targetHost + "/excluded\\..*");

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/excluded.Service/Method", headers);
        assertNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));

        // a non-matching path is still processed
        Metadata included = new Metadata();
        ApproovService.addApproov(targetHost, "/other.Service/Method", included);
        assertNotNull(included.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    public void testRemoveExclusionUrlRegexRevertsToProcessing() throws Exception {
        reinitWithProtectedHost();
        String regex = "https://" + targetHost + "/excluded\\..*";
        ApproovService.addExclusionURLRegex(regex);
        ApproovService.removeExclusionURLRegex(regex);

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/excluded.Service/Method", headers);
        assertNotNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    public void testTraceIDHeaderEmittedByDefaultAndCustomizable() throws Exception {
        reinitWithProtectedHost();
        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);
        assertNotNull(headers.get(Metadata.Key.of("Approov-TraceID", Metadata.ASCII_STRING_MARSHALLER)));

        // a custom trace ID header name is respected
        reinitWithProtectedHost();
        ApproovService.setApproovTraceIDHeader("X-Trace");
        Metadata custom = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", custom);
        assertNotNull(custom.get(Metadata.Key.of("X-Trace", Metadata.ASCII_STRING_MARSHALLER)));
        assertNull(custom.get(Metadata.Key.of("Approov-TraceID", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    public void testInitializeResetsCustomMutatorToDefault() throws Exception {
        reinitWithProtectedHost();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning());
        assertFalse(ApproovService.getServiceMutator() == ApproovServiceMutator.DEFAULT);

        // a successful re-initialization must reset the mutator to the default (§1 Service Mutator Reset)
        ApproovService.initialize(context, validInitialConfig);
        assertTrue(ApproovService.getServiceMutator() == ApproovServiceMutator.DEFAULT);
    }

    @Test
    public void testCustomMutatorCanBlockTokenAddition() throws Exception {
        reinitWithProtectedHost();
        // a custom mutator that refuses to add the token (returns false from the fetch-result hook)
        ApproovService.setServiceMutator(new ApproovServiceMutator() {
            @Override
            public boolean handleInterceptorFetchTokenResult(Approov.TokenFetchResult approovResults, String url) {
                return false;
            }
        });
        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);
        assertNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
    }

    // ==================================================================================
    // SECTION 5: Message Signing
    // ==================================================================================

    @Test
    public void testInstallMessageSigningAddsSignatureHeaders() throws Exception {
        reinitWithProtectedHost();
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseInstallMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);

        assertNotNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
        String sigInput = headers.get(Metadata.Key.of("Signature-Input", Metadata.ASCII_STRING_MARSHALLER));
        String sig = headers.get(Metadata.Key.of("Signature", Metadata.ASCII_STRING_MARSHALLER));
        assertNotNull(sigInput);
        assertNotNull(sig);
        assertTrue(sigInput.startsWith("install="));
        assertTrue(sig.startsWith("install=:"));
    }

    @Test
    public void testAccountMessageSigningAddsSignatureHeaders() throws Exception {
        reinitWithProtectedHost();
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()
                .setUseAccountMessageSigning();
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);

        assertNotNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));
        String sigInput = headers.get(Metadata.Key.of("Signature-Input", Metadata.ASCII_STRING_MARSHALLER));
        String sig = headers.get(Metadata.Key.of("Signature", Metadata.ASCII_STRING_MARSHALLER));
        assertNotNull(sigInput);
        assertNotNull(sig);
        assertTrue(sigInput.startsWith("account="));
        assertTrue(sig.startsWith("account=:"));
    }

    @Test
    public void testMessageSigningSkippedForUnprotectedHost() throws Exception {
        // an unprotected host yields no token, so no signature headers should be added (no token = no signing)
        AttesterProxyController.reset();
        AttesterProxyController.loadScenarioJson(scenarioJson(uniqueCaseName("no-protected"), "\"protectedDomains\": []"));
        ApproovService.resetForTesting();
        ApproovService.initialize(context, validInitialConfig);

        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning()
            .setDefaultFactory(ApproovDefaultMessageSigning.generateDefaultSignatureParametersFactory()));

        Metadata headers = new Metadata();
        ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);
        assertNull(headers.get(Metadata.Key.of("Signature", Metadata.ASCII_STRING_MARSHALLER)));
        assertNull(headers.get(Metadata.Key.of("Signature-Input", Metadata.ASCII_STRING_MARSHALLER)));
    }

    @Test
    public void testUnsupportedAlgorithmFailsClosed() throws Exception {
        reinitWithProtectedHost();
        // a factory configured with an unsupported algorithm must fail closed
        ApproovDefaultMessageSigning.SignatureParametersFactory factory =
            new ApproovDefaultMessageSigning.SignatureParametersFactory() {
                @Override
                protected io.approov.util.sig.SignatureParameters buildSignatureParameters(
                        ApproovDefaultMessageSigning.ApproovGRPCComponentProvider provider,
                        ApproovRequestMutations changes) {
                    io.approov.util.sig.SignatureParameters params = super.buildSignatureParameters(provider, changes);
                    params.setAlg("unsupported-alg");
                    return params;
                }
            }
            .setBaseParameters(new io.approov.util.sig.SignatureParameters())
            .setAddApproovTokenHeader(true);
        ApproovService.setServiceMutator(new ApproovDefaultMessageSigning().setDefaultFactory(factory));

        Metadata headers = new Metadata();
        try {
            ApproovService.addApproov(targetHost, "/pkg.Service/Method", headers);
            fail("Expected ApproovException for unsupported algorithm");
        } catch (ApproovException e) {
            assertTrue(e.getMessage().contains("Unsupported algorithm"));
        }
    }

    @Test
    public void testDecodeASN1DERES256SignatureProducesRawForm() {
        // a minimal valid ASN.1 DER ES256 signature: SEQ { INTEGER 0x01, INTEGER 0x02 }
        byte[] der = new byte[] { 0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02 };
        byte[] raw = ApproovDefaultMessageSigning.decodeASN1DERES256Signature(der);
        assertEquals(64, raw.length);
        assertEquals(0x01, raw[31]);
        assertEquals(0x02, raw[63]);
    }
}
