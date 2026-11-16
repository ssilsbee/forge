package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.llm.PrivateFiles;
import org.testng.annotations.Test;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.testng.Assert.*;

public class ChatGptAuthLifecycleTest {
    private final ChatGptAuth.Storage storage = new ChatGptAuth.Storage() {
        @Override public JsonObject read(Path path) throws IOException { return LlmHttp.object(Files.readString(path)); }
        @Override public void write(Path path, JsonObject value) throws IOException { PrivateFiles.write(path, value.toString()); }
    };
    private Path connected() throws IOException {
        Path directory = Files.createTempDirectory("auth-lifecycle-test");
        String id = UUID.randomUUID().toString();
        JsonObject settings = new JsonObject(); settings.addProperty("activeProfile", id);
        storage.write(directory.resolve("settings.json"), settings);
        JsonObject profile = new JsonObject();
        profile.addProperty("profileId", id); profile.addProperty("client_id", "test-client");
        profile.addProperty("access_token", "fake-test-token"); profile.addProperty("scope", "chatgpt.tokens.use.direct");
        profile.addProperty("expires_at", Instant.now().getEpochSecond() + 3600);
        storage.write(directory.resolve("profiles").resolve(id + ".json"), profile);
        return directory;
    }
    private void authorize(URI uri) throws Exception {
        Map<String, String> query = new HashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        HttpURLConnection callback = (HttpURLConnection) URI.create(query.get("redirect_uri")
                + "?state=" + query.get("state") + "&code=fake-code&client_id=test-client").toURL().openConnection();
        callback.setConnectTimeout(2000); callback.setReadTimeout(2000);
        try { assertEquals(callback.getResponseCode(), 200); }
        finally { callback.disconnect(); }
    }
    @Test public void browserWaitDoesNotBlockCredentialReadsAndCanBeInterrupted() throws Exception {
        CompletableFuture<URI> browser = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(connected(), browser::complete, storage, Duration.ofSeconds(10));
        var executor = Executors.newFixedThreadPool(2);
        var login = executor.submit(() -> auth.login(null, false));
        try {
            browser.get(2, TimeUnit.SECONDS);
            executor.submit(() -> {
                assertEquals(auth.accessToken(null), "fake-test-token");
                assertEquals(auth.profiles().size(), 1);
                return null;
            }).get(2, TimeUnit.SECONDS);
            assertFalse(login.isDone());
            login.cancel(true);
            assertEquals(executor.submit(() -> auth.profiles().size()).get(2, TimeUnit.SECONDS).intValue(), 1);
        } finally { login.cancel(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); }
    }
    @Test public void foregroundWaitAllowsLogoutAndCannotRestoreSignedOutCredentials() throws Exception {
        CompletableFuture<URI> browser = new CompletableFuture<>();
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        ChatGptAuth auth = new ChatGptAuth(connected(), browser::complete, storage, Duration.ofSeconds(10), () -> {
            waiting.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Test gate timed out"); } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled", e); }
        });
        var executor = Executors.newFixedThreadPool(2);
        var login = executor.submit(() -> auth.login(null, false));
        try {
            authorize(browser.get(2, TimeUnit.SECONDS));
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(executor.submit(() -> auth.logout(null)).get(2, TimeUnit.SECONDS));
            release.countDown();
            try { login.get(2, TimeUnit.SECONDS); fail("A signed-out attempt must not exchange tokens"); }
            catch (ExecutionException e) {
                assertTrue(e.getCause() instanceof IOException);
                assertTrue(e.getCause().getMessage().contains("cancelled or replaced"));
            }
            assertThrows(IOException.class, () -> auth.accessToken(null));
        } finally { release.countDown(); login.cancel(true); executor.shutdownNow(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
