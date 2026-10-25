package forge.llm;

import org.testng.annotations.Test;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import static org.testng.Assert.*;

public class OAuthLoopbackTest {
    @Test public void anUncompletedAttemptHonorsItsConfiguredDeadline() throws Exception {
        try (OAuthLoopback listener = new OAuthLoopback("state", "issued")) {
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> listener.await(java.time.Duration.ofMillis(30)));
        }
    }

    private int request(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    @Test public void rejectsBadCallbacksWithoutConsumingTheValidAttempt() throws Exception {
        try (OAuthLoopback listener = new OAuthLoopback("expected-state", null)) {
            assertTrue(listener.redirect().startsWith("http://127.0.0.1:"));
            assertEquals(request(listener.redirect() + "?state=wrong&code=code&client_id=issued"), 400);
            assertEquals(request(listener.redirect().replace("/auth/callback", "/wrong") + "?state=expected-state"), 400);
            assertEquals(request(listener.redirect() + "?state=expected-state&code=code&client_id=issued"), 200);
            var result = listener.await();
            assertEquals(result.get("client_id"), "issued");
            assertEquals(result.get("code"), "code");
        }
    }

    @Test public void closingListenerReleasesAnAwaitingSignIn() throws Exception {
        OAuthLoopback listener = new OAuthLoopback("state", "issued");
        CompletableFuture<Boolean> waiter = CompletableFuture.supplyAsync(() -> {
            try { listener.await(); return false; }
            catch (Exception e) { return true; }
        });
        listener.close();
        assertTrue(waiter.get(2, java.util.concurrent.TimeUnit.SECONDS));
    }
}
