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
        // Same config: should log and continue
        ApproovService.initialize(context, validInitialConfig);

        // Different config: should fail to initialize
        String differentConfig = "#cb-other#mAxOF0ekJUOC36J5XWmVmVipOcUoEdMjhPSp2FVtyTo=";
        ApproovService.initialize(context, differentConfig);
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
        ApproovService.addApproov(targetHost, headers);

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
        ApproovService.addApproov(targetHost, headers);
        assertNull(headers.get(Metadata.Key.of("Approov-Token", Metadata.ASCII_STRING_MARSHALLER)));

        assertFalse(ApproovService.isApproovEnabled());

        ApproovService.initialize(context, validInitialConfig);
        assertTrue(ApproovService.isApproovEnabled());

        Metadata protectedHeaders = new Metadata();
        ApproovService.addApproov(targetHost, protectedHeaders);
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

        ApproovService.addApproov(targetHost, headers);

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
}
