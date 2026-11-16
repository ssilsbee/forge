package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.llm.LiveDecisionBridge;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Shared connection jobs and advisor ownership. Every UI callback runs on the supplied UI executor. */
public final class AdvisorController implements AutoCloseable {
    public interface View {
        void status(String message);
        default void busy(boolean value) { }
        default void paused() throws IOException { }
        /** Persist the platform's settings before the advisor becomes active. */
        default void beforeEnable(AdvisorSettings settings) throws IOException { }
        LiveAdvisor.Monitor monitor(AdvisorSettings settings);
    }
    @FunctionalInterface interface Clients { ProviderClient create(AdvisorSettings settings) throws IOException; }
    @FunctionalInterface interface Advisors { LiveAdvisor create(ProviderClient client, AdvisorSettings settings, Path directory); }
    @FunctionalInterface private interface Job<T> { T run() throws Exception; }
    private final ChatGptAuth auth;
    private final Path directory;
    private final Executor ui;
    private final View view;
    private final Clients clients;
    private final Advisors advisors;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "forge-advisor-connection"); thread.setDaemon(true); return thread;
    });
    private Future<?> operation;
    private long generation;
    private boolean closed;
    private LiveAdvisor advisor;
    private AdvisorSettings activeSettings;

    public AdvisorController(Path directory, ChatGptAuth auth, RulesRepository rules, Executor ui, View view) {
        this(directory, auth, ui, view, settings -> {
            ProviderClient client = settings.provider() == ProviderClient.Provider.CHATGPT
                    ? ProviderClient.chatGpt(auth, null) : ProviderClient.openRouter(settings.openRouterKey());
            return client.withRules(rules);
        }, (client, settings, path) -> new LiveAdvisor(client, settings.model(), settings.effort(), settings.timeout(), path));
    }

    AdvisorController(Path directory, ChatGptAuth auth, Executor ui, View view, Clients clients, Advisors advisors) {
        this.directory = directory; this.auth = auth; this.ui = ui; this.view = view;
        this.clients = clients; this.advisors = advisors;
    }

    public void profiles(Consumer<JsonArray> result) {
        submit("Checking the saved ChatGPT connection…", () -> {
            try { return new Profiles(auth.profiles(), null); }
            catch (IOException e) { return new Profiles(new JsonArray(), e.getMessage()); }
        }, loaded -> {
            if (loaded.error() != null) { view.status(loaded.error()); }
            // Account display failure must not prevent restoring an unrelated OpenRouter connection.
            result.accept(loaded.profiles());
        });
    }
    private record Profiles(JsonArray profiles, String error) { }
    public void models(AdvisorSettings settings, Consumer<JsonArray> result) {
        submit("Refreshing available models…", () -> clients.create(settings).models(), catalog -> {
            result.accept(catalog); view.status("Connection checked. Model list updated.");
        });
    }
    public void signIn(Consumer<JsonArray> result) {
        pause();
        submit("Waiting for ChatGPT browser sign-in…", () -> {
            auth.login(null, false); return auth.profiles();
        }, profiles -> { result.accept(profiles); view.status("ChatGPT connected. Choose a model and enable the advisor."); });
    }
    public void signOut(Consumer<JsonArray> result) {
        pause();
        submit("Disconnecting ChatGPT…", () -> {
            boolean revoked = auth.logout(null);
            return new SignOut(auth.profiles(), revoked);
        }, outcome -> {
            result.accept(outcome.profiles());
            view.status(outcome.revoked() ? "ChatGPT disconnected. Forge AI is in control."
                    : "Signed out locally. Remote sign-out was not confirmed; disconnect Forge in ChatGPT Settings.");
        });
    }
    private record SignOut(JsonArray profiles, boolean revoked) { }

    public synchronized void enable(AdvisorSettings settings) {
        if (closed) { return; }
        cancelLocked();
        if (advisor != null) { advisor.pause(); }
        submit("Checking the selected connection and model…", () -> {
            ProviderClient client = clients.create(settings);
            boolean found = false;
            for (var model : client.models()) { found |= settings.model().equals(model.getAsJsonObject().get("id").getAsString()); }
            if (!found) { throw new IOException("The selected model is not listed by this connection. Refresh models or check its model ID."); }
            return client;
        }, client -> {
            try {
                long accepted = generation;
                view.beforeEnable(settings);
                if (closed || generation != accepted) { return; }
                LiveAdvisor.Monitor monitor = view.monitor(settings);
                if (closed || generation != accepted) { return; }
                if (advisor == null || !settings.equals(activeSettings)) {
                    if (advisor != null) { advisor.close(); }
                    advisor = advisors.create(client, settings, directory);
                    activeSettings = settings;
                }
                advisor.setMonitor(monitor);
                LiveDecisionBridge.install(advisor);
                advisor.resumeLive();
                view.status("Advisor enabled. Start a two-player Constructed or Commander game with an AI opponent.");
            } catch (IOException e) { view.status("Could not save advisor settings. Forge remains in control."); }
        });
    }

    public void test(AdvisorSettings settings, LiveAdvisor.Monitor monitor) {
        submit("Testing the selected model (uses provider allowance)…", () -> {
            ProviderClient client = clients.create(settings);
            String id = "connection-" + java.util.UUID.randomUUID();
            String instructions = "Connection check for Forge. Return only the JSON object {\"connected\":true}.";
            JsonObject input = new JsonObject(); input.addProperty("request", "Confirm the connection.");
            monitor.request(id, instructions, input);
            long started = System.nanoTime();
            try {
                ProviderClient.Completion reply = client.completeWithInstructions(instructions, settings.model(), settings.effort(), 8192, input, settings.timeout());
                JsonObject report = new JsonObject(); report.addProperty("responseText", reply.text());
                report.addProperty("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
                report.addProperty("provider", client.name()); report.addProperty("model", settings.model()); report.add("usage", reply.usage());
                monitor.response(id, report);
                JsonObject answer = LlmHttp.object(reply.text());
                if (!answer.has("connected") || !answer.get("connected").isJsonPrimitive()
                        || !answer.get("connected").getAsJsonPrimitive().isBoolean() || !answer.get("connected").getAsBoolean()) {
                    throw new IOException("Model returned an unexpected connection-check answer.");
                }
                return "Connection verified with " + settings.model() + ". Enable the advisor to start playing.";
            } catch (Exception e) {
                JsonObject report = new JsonObject(); report.addProperty("status", "connection_failed");
                report.addProperty("error", safeError(e)); monitor.response(id, report); throw e;
            }
        }, view::status);
    }

    private synchronized <T> void submit(String message, Job<T> job, Consumer<T> result) {
        if (closed) { return; }
        if (operation != null) { dispatch(generation, () -> view.status("A connection request is already running. Cancel it first if needed.")); return; }
        long version = ++generation;
        dispatch(version, () -> { view.busy(true); view.status(message); });
        operation = worker.submit(() -> {
            try {
                T value = job.run();
                dispatch(version, () -> { operation = null; view.busy(false); result.accept(value); });
            } catch (Exception | LinkageError e) {
                dispatch(version, () -> { operation = null; view.busy(false); view.status(safeError(e)); });
            }
        });
    }
    private static String safeError(Throwable e) {
        return e instanceof IOException ? e.getMessage() : "Connection request failed. Check the selected connection and settings.";
    }
    private void dispatch(long version, Runnable callback) {
        ui.execute(() -> {
            synchronized (AdvisorController.this) {
                if (!closed && generation == version) { callback.run(); }
            }
        });
    }
    private void cancelLocked() {
        generation++;
        if (operation != null) { operation.cancel(true); operation = null; }
    }
    public synchronized void cancel() {
        if (closed) { return; }
        cancelLocked();
        dispatch(generation, () -> { view.busy(false); view.status("Connection request cancelled."); });
    }
    public synchronized void pause() {
        if (closed) { return; }
        cancelLocked();
        if (advisor != null) { advisor.pause(); }
        // Persist the user's pause even if the next action supersedes its status notification.
        // The platform UI executor serializes this before any subsequent successful enable.
        long version = generation;
        ui.execute(() -> {
            synchronized (AdvisorController.this) {
                if (!closed) {
                    String message = "LLM paused. Forge AI is in control.";
                    try { view.paused(); }
                    catch (IOException e) { message = "LLM paused, but the startup preference could not be saved."; }
                    if (generation == version) { view.busy(false); view.status(message); }
                }
            }
        });
    }
    public synchronized boolean isEnabled() { return advisor != null && advisor.isEnabled(); }
    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true; cancelLocked();
        if (advisor != null) { advisor.close(); LiveDecisionBridge.uninstall(advisor); advisor = null; }
        worker.shutdownNow();
    }
}
