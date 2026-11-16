package forge.llm;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import org.testng.annotations.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class AdvisorControllerTest {
    private static AdvisorSettings settings(String key) {
        return new AdvisorSettings(ProviderClient.Provider.OPENROUTER, "test-model", "high", 3, key);
    }
    private static class Harness implements AutoCloseable, AdvisorController.View, LiveAdvisor.Monitor {
        final Thread owner = Thread.currentThread();
        final LinkedBlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        final List<LiveAdvisor> created = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final CompletableFuture<URI> browser = new CompletableFuture<>();
        final AtomicInteger catalogs = new AtomicInteger(), tests = new AtomicInteger();
        final HttpServer server;
        final AdvisorController controller;
        final Path directory;
        boolean busy, startupEnabled, failSave;
        int pauses, enables;
        volatile String testAnswer = "{\"connected\":true}";
        final List<JsonObject> outputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        Harness() throws IOException {
            directory = Files.createTempDirectory("advisor-controller-test");
            ChatGptAuth auth = new ChatGptAuth(directory.resolve("connections"), browser::complete, new ChatGptAuth.Storage() {
                @Override public JsonObject read(Path path) throws IOException { return LlmHttp.object(Files.readString(path)); }
                @Override public void write(Path path, JsonObject value) throws IOException { PrivateFiles.write(path, value.toString()); }
            }, Duration.ofSeconds(10));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/models", exchange -> {
                catalogs.incrementAndGet();
                byte[] bytes = "{\"data\":[{\"id\":\"test-model\",\"name\":\"Test\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.createContext("/chat/completions", exchange -> {
                tests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                JsonObject message = new JsonObject(); message.addProperty("content", testAnswer);
                byte[] bytes = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            controller = new AdvisorController(directory, auth, ui::add, this,
                    config -> new ProviderClient(ProviderClient.Provider.OPENROUTER,
                            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> config.openRouterKey()),
                    (client, config, path) -> {
                        LiveAdvisor advisor = new LiveAdvisor(client, config.model(), config.effort(), config.timeout(), path);
                        created.add(advisor); return advisor;
                    });
        }
        void uiThread() { assertSame(Thread.currentThread(), owner, "Native UI callbacks must stay on the UI thread"); }
        @Override public void status(String message) { uiThread(); statuses.add(message); }
        @Override public void busy(boolean value) { uiThread(); busy = value; }
        @Override public void paused() { uiThread(); pauses++; startupEnabled = false; }
        @Override public void beforeEnable(AdvisorSettings config) throws IOException {
            uiThread(); if (failSave) { throw new IOException("test save failure"); }
            enables++; startupEnabled = true;
        }
        @Override public LiveAdvisor.Monitor monitor(AdvisorSettings settings) { uiThread(); return this; }
        @Override public void request(String id, String instructions, JsonObject input) { }
        @Override public void response(String id, JsonObject output) { outputs.add(output.deepCopy()); }
        @Override public void status(String id, String message) { }
        void pump() { Runnable callback; while ((callback = ui.poll()) != null) { callback.run(); } }
        void until(BooleanSupplier done) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!done.getAsBoolean()) {
                if (System.nanoTime() > end) { fail("Timed out waiting for the shared controller"); }
                Runnable callback = ui.poll(50, TimeUnit.MILLISECONDS);
                if (callback != null) { callback.run(); }
            }
            pump();
        }
        void completed() throws Exception {
            until(() -> busy); until(() -> !busy);
        }
        void awaitQueuedCompletion() throws Exception {
            // Leave both the busy event and completed network result in the native event queue.
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ui.size() < 2) {
                if (System.nanoTime() > end) { fail("Missing queued result"); }
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }
        @Override public void close() { controller.close(); pump(); server.stop(0); }
    }

    public void unchangedSettingsResumeTheSameSessionAndRotatedCredentialsReplaceIt() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(settings("first-key")); h.completed();
            assertTrue(h.controller.isEnabled()); assertTrue(h.startupEnabled);
            LiveAdvisor first = h.created.get(0);
            h.controller.pause(); h.pump(); assertFalse(first.isEnabled()); assertFalse(h.startupEnabled);
            h.controller.enable(settings("first-key")); h.completed();
            assertEquals(h.created.size(), 1, "Resume must retain plans, memory, budgets and diagnostics");
            assertTrue(first.isEnabled());
            h.controller.enable(settings("rotated-key")); h.completed();
            assertEquals(h.created.size(), 2); assertFalse(first.isEnabled()); assertTrue(h.controller.isEnabled());
        }
    }
    public void pauseRejectsAlreadyQueuedEnableResultsAndPersistsItsPreference() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(settings("key")); h.awaitQueuedCompletion();
            h.controller.pause(); h.pump();
            assertEquals(h.created.size(), 0); assertEquals(h.enables, 0);
            assertFalse(h.busy); assertEquals(h.pauses, 1);
            h.controller.enable(settings("key")); h.completed(); assertTrue(h.controller.isEnabled());
        }
    }
    public void closeRejectsQueuedCallbacksWithoutEnablingOrTouchingTheOldUi() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(settings("key")); h.awaitQueuedCompletion();
            h.controller.close(); h.pump();
            assertTrue(h.statuses.isEmpty()); assertTrue(h.created.isEmpty()); assertEquals(h.enables, 0);
            h.controller.enable(settings("key")); h.pump(); assertFalse(h.controller.isEnabled());
        }
    }
    public void closingAnOlderControllerDoesNotUninstallItsReplacement() throws Exception {
        try (Harness older = new Harness(); Harness replacement = new Harness()) {
            older.controller.enable(settings("key")); older.completed();
            replacement.controller.enable(settings("key")); replacement.completed();
            older.controller.close();
            assertTrue(LiveDecisionBridge.uninstall(replacement.created.get(0)), "Older UI must not clear the replacement's game bridge");
        }
    }
    public void modelRefreshKeepsTheAdvisorRunningAndCallbacksOnTheUiThread() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(settings("key")); h.completed();
            AtomicInteger models = new AtomicInteger();
            h.controller.models(settings("key"), catalog -> { h.uiThread(); models.set(catalog.size()); }); h.completed();
            assertEquals(models.get(), 1); assertTrue(h.controller.isEnabled()); assertEquals(h.created.size(), 1);
        }
    }
    public void unknownModelsAndPersistenceFailuresLeaveForgeInControl() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(new AdvisorSettings(ProviderClient.Provider.OPENROUTER, "missing", "high", 3, "key")); h.completed();
            assertFalse(h.controller.isEnabled()); assertEquals(h.enables, 0); assertTrue(h.created.isEmpty());
            assertTrue(h.statuses.get(h.statuses.size() - 1).contains("not listed"));
            h.failSave = true; h.controller.enable(settings("key")); h.completed();
            assertFalse(h.controller.isEnabled()); assertFalse(h.startupEnabled); assertTrue(h.created.isEmpty());
        }
    }
    public void signInPausesTheGameAndCanBeCancelledWithoutAStaleError() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.enable(settings("key")); h.completed();
            h.controller.signIn(profiles -> fail("Cancelled browser sign-in must not succeed"));
            h.until(() -> h.busy && h.pauses > 0);
            h.browser.get(3, TimeUnit.SECONDS);
            assertFalse(h.controller.isEnabled()); assertFalse(h.startupEnabled);
            h.controller.cancel(); h.pump(); assertFalse(h.busy);
            h.controller.enable(settings("key")); h.completed(); assertTrue(h.controller.isEnabled());
            assertEquals(h.created.size(), 1, "A cancelled sign-in must not discard the existing session");
        }
    }
    public void connectionTestsUseSharedValidationWithoutEnablingPlay() throws Exception {
        try (Harness h = new Harness()) {
            h.controller.test(settings("key"), h); h.completed();
            assertEquals(h.tests.get(), 1); assertFalse(h.controller.isEnabled());
            assertTrue(h.statuses.get(h.statuses.size() - 1).contains("Connection verified"));
            h.testAnswer = "{\"connected\":\"true\"}";
            h.controller.test(settings("key"), h); h.completed();
            assertTrue(h.statuses.get(h.statuses.size() - 1).contains("unexpected"));
            assertEquals(h.outputs.get(h.outputs.size() - 1).get("status").getAsString(), "connection_failed");
        }
    }
    public void brokenChatGptProfileDoesNotPreventRestoringOpenRouter() throws Exception {
        try (Harness h = new Harness()) {
            Files.createDirectories(h.directory.resolve("connections"));
            Files.writeString(h.directory.resolve("connections/settings.json"), "broken JSON");
            h.controller.profiles(profiles -> {
                assertTrue(profiles.isEmpty());
                h.controller.enable(settings("key"));
            });
            h.until(() -> h.controller.isEnabled());
            assertTrue(h.startupEnabled); assertEquals(h.created.size(), 1);
        }
    }
    public void sharedSettingsValidateInputAndNeverPrintApiKeys() {
        assertEquals(settings("secret-key").model(), "test-model");
        assertFalse(settings("secret-key").toString().contains("secret-key"));
        assertThrows(IllegalArgumentException.class, () -> new AdvisorSettings(ProviderClient.Provider.CHATGPT, " ", "high", 60, null));
        assertThrows(IllegalArgumentException.class, () -> new AdvisorSettings(ProviderClient.Provider.CHATGPT, "model", "invalid", 60, null));
        assertThrows(IllegalArgumentException.class, () -> new AdvisorSettings(ProviderClient.Provider.CHATGPT, "model", "high", 0, null));
        assertThrows(IllegalArgumentException.class, () -> new AdvisorSettings(ProviderClient.Provider.CHATGPT, "model", "high", 301, null));
    }
}
