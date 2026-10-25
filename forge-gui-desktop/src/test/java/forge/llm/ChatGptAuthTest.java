package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import static org.testng.Assert.*;

public class ChatGptAuthTest {
    private KeyPair pair;
    private JsonObject jwks;

    @BeforeClass
    public void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        JsonObject key = new JsonObject();
        key.addProperty("kty", "RSA");
        key.addProperty("kid", "unit-test-key");
        key.addProperty("n", unsigned(publicKey.getModulus().toByteArray()));
        key.addProperty("e", unsigned(publicKey.getPublicExponent().toByteArray()));
        JsonArray keys = new JsonArray();
        keys.add(key);
        jwks = new JsonObject();
        jwks.add("keys", keys);
    }

    private static String unsigned(byte[] bytes) {
        if (bytes[0] == 0) { bytes = Arrays.copyOfRange(bytes, 1, bytes.length); }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private JsonObject claims() {
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", "https://auth.openai.com");
        claims.addProperty("aud", "oaiapp_test");
        claims.addProperty("sub", "test-subject");
        claims.addProperty("nonce", "test-nonce");
        claims.addProperty("exp", Instant.now().getEpochSecond() + 60);
        return claims;
    }

    private String jwt(JsonObject claims) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString("{\"alg\":\"RS256\",\"kid\":\"unit-test-key\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(data.getBytes(StandardCharsets.US_ASCII));
        return data + "." + encoder.encodeToString(signature.sign());
    }

    @Test
    public void verifiesSignedIdentityAndRejectsTampering() throws Exception {
        String token = jwt(claims());
        assertEquals(ChatGptAuth.verifyIdentity(token, "oaiapp_test", "test-nonce", jwks, Instant.now())
                .get("sub").getAsString(), "test-subject");
        String[] parts = token.split("\\.");
        JsonObject changed = claims();
        changed.addProperty("sub", "attacker");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(changed.toString().getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThrows(IOException.class, () -> ChatGptAuth.verifyIdentity(tampered, "oaiapp_test", "test-nonce", jwks, Instant.now()));
    }

    @Test
    public void checksNonceAudienceIssuerAndExpiry() throws Exception {
        String token = jwt(claims());
        assertThrows(IOException.class, () -> ChatGptAuth.verifyIdentity(token, "oaiapp_test", "wrong-nonce", jwks, Instant.now()));
        assertThrows(IOException.class, () -> ChatGptAuth.verifyIdentity(token, "other-client", "test-nonce", jwks, Instant.now()));
        for (String invalid : new String[] {"issuer", "expiry"}) {
            JsonObject claims = claims();
            if (invalid.equals("issuer")) { claims.addProperty("iss", "https://attacker.example"); }
            else { claims.addProperty("exp", Instant.now().getEpochSecond() - 60); }
            String bad = jwt(claims);
            assertThrows(IOException.class, () -> ChatGptAuth.verifyIdentity(bad, "oaiapp_test", "test-nonce", jwks, Instant.now()));
        }
    }

    @Test
    public void initialCallbackMustSupplyIssuedClientAndCorrectState() throws Exception {
        assertEquals(ChatGptAuth.callbackParameters("state=expected&code=one&client_id=oaiapp_test", "expected", null)
                .get("client_id"), "oaiapp_test");
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=wrong&code=one&client_id=oaiapp_test", "expected", null));
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=expected&code=one", "expected", null));
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=expected&code=one&client_id=dynamic_agent_client", "expected", null));
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=expected&state=expected&code=one&client_id=oaiapp_test", "expected", null));
    }

    @Test
    public void reconnectCannotReplaceRegistrationAndDenialStillValidatesState() throws Exception {
        assertEquals(ChatGptAuth.callbackParameters("state=expected&code=one", "expected", "oaiapp_test").get("code"), "one");
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=expected&code=one&client_id=other", "expected", "oaiapp_test"));
        assertTrue(ChatGptAuth.callbackParameters("state=expected&error=access_denied", "expected", null).containsKey("error"));
        assertThrows(IOException.class, () -> ChatGptAuth.callbackParameters("state=wrong&error=access_denied", "expected", null));
    }
}
