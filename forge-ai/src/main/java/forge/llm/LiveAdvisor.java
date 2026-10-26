package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import forge.game.player.Player;


import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared opt-in advisor; only detached snapshots reach its provider worker. */
public class LiveAdvisor implements LiveDecisionBridge.Advisor, AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private final ProviderClient provider;
    private final String model;
    private final String effort;
    private final Duration timeout;
    private final Path directory;
    private final Path session;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "forge-live-llm");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    // Worker only. A null value records a failed attempt so later moves do not retry planning.
    private final Map<String, JsonObject> plans = new HashMap<>();
    private final StrategyMemory memory = new StrategyMemory(); // worker only
    private final Map<Player, Budget> budgets = new WeakHashMap<>(); // game thread only
    private volatile Future<?> pending;
    private final List<Future<?>> preparations = new ArrayList<>(); // guarded by this
    private volatile Monitor monitor;
    private final AdvisorDiagnostics diagnostics = new AdvisorDiagnostics();
    private long lastInsights;
    private final AtomicInteger failures = new AtomicInteger();
    private static final class Budget {
        final String memoryKey = UUID.randomUUID().toString();
        int turn = -1; int count; int choices; int total;
    }

    @Override public void completed(String decisionId, boolean accepted) {
        try {
            worker.execute(() -> {
                if (memory.complete(decisionId, accepted)) { diagnostic("Strategy notes", "Updated after accepted choice"); }
            });
        } catch (RejectedExecutionException ignored) { /* Closing an advisor discards its session memory. */ }
    }

    public LiveAdvisor(ProviderClient provider, String model, String effort, Duration timeout, Path directory) {
        this.provider = provider;
        this.model = model;
        this.effort = effort;
        this.timeout = timeout;
        this.directory = directory;
        session = directory.resolve("live").resolve("session-" + Instant.now().toEpochMilli());
    }

    public interface Monitor {
        void request(String id, String instructions, JsonObject input);
        void response(String id, JsonObject output);
        void status(String id, String message);
        default boolean isDisplayThread() { return false; }
        default void insights(String report) { }
    }

    public void setMonitor(Monitor value) { monitor = value; publishInsights(true); }
    public String insights() { return diagnostics.render(); }
    @Override public void diagnostic(String category, String detail) {
        diagnostics.count(category, detail);
        publishInsights(false);
    }
    private synchronized void publishInsights(boolean force) {
        long now = System.nanoTime();
        if (monitor != null && (force || now - lastInsights >= 250_000_000L)) {
            lastInsights = now;
            monitor.insights(diagnostics.render());
        }
    }
    @Override public boolean isEnabled() { return enabled.get(); }
    public void resumeLive() {
        failures.set(0);
        enabled.set(true);
        status("", "LLM enabled for supported actions, attacks, blocks, card choices, scry and surveil.");
    }

    @Override public void close() { pause(); worker.shutdownNow(); }

    public synchronized void pause() {
        enabled.set(false);
        Future<?> task = pending;
        if (task != null) { task.cancel(true); }
        for (Future<?> plan : preparations) { plan.cancel(true); }
        preparations.clear();
        status("", "Luna paused. Forge is in control. Enable Luna to resume.");
    }

    @Override public synchronized void prepare(Player player) {
        if (!enabled.get()) { return; }
        try {
            // Snapshot only the known unordered deck on the game thread, before any background work.
            JsonObject deck = DeckStrategyPlanner.deckInput(player.getRegisteredPlayer().getDeck());
            String deckHash = DeckStrategyPlanner.digest(deck.toString());
            String id = "pregame-" + UUID.randomUUID();
            preparations.removeIf(Future::isDone);
            preparations.add(worker.submit(() -> {
                if (!enabled.get()) { return null; }
                return preparePlan(id, deck, deckHash);
            }));
        } catch (IOException e) {
            status("", "Early deck preparation unavailable; LLM play will prepare on its first decision.");
        }
    }

    @Override public String choose(Player player, JsonObject question) throws Exception {
        if (!enabled.get()) { throw new IOException("Luna is paused."); }
        if (monitor != null && monitor.isDisplayThread()) { throw new IOException("Never wait for a model on the display thread."); }
        Budget budget = budgets.computeIfAbsent(player, p -> new Budget());
        int turn = player.getGame().getPhaseHandler().getTurn();
        if (budget.turn != turn) { budget.turn = turn; budget.count = 0; budget.choices = 0; }
        int count = question.has("choiceKind") ? ++budget.choices : ++budget.count;
        if (count > 20 || ++budget.total > 150) { throw new IOException("Live request budget reached; Forge continues."); }
        // Freeze all game-derived data before dispatch. No live player/card/deck object reaches the worker.
        JsonObject deck = DeckStrategyPlanner.deckInput(player.getRegisteredPlayer().getDeck());
        String deckHash = DeckStrategyPlanner.digest(deck.toString());
        JsonObject detached = question.deepCopy();
        String id = detached.get("decisionId").getAsString();
        long queued = System.nanoTime();
        String kind = detached.has("choiceKind") ? detached.get("choiceKind").getAsString() : "Action selection";
        Future<String> task = worker.submit(() -> review(id, detached, deck, deckHash, queued, kind, budget.memoryKey, turn));
        pending = task;
        try {
            String selected = task.get(timeout.toSeconds() * 2 + 5, TimeUnit.SECONDS);
            if (!enabled.get()) { throw new IOException("Luna paused before applying response."); }
            failures.set(0);
            return selected;
        } catch (Exception e) {
            task.cancel(true);
            if (enabled.get() && failures.incrementAndGet() >= 3) { pause(); status(id, "Three requests failed. Luna paused; Forge continues."); }
            throw e;
        } finally {
            pending = null;
            diagnostics.time("Game wait — " + kind, (System.nanoTime() - queued) / 1_000_000);
            publishInsights(true);
        }
    }

    private JsonObject preparePlan(String id, JsonObject deck, String deckHash) throws IOException {
        JsonObject plan = plans.get(deckHash);
        if (!plans.containsKey(deckHash)) {
            long started = System.nanoTime();
            diagnostic("Deck preparation", "Attempted");
            plans.put(deckHash, null);
            String planId = id + " / deck plan";
            if (monitor != null) { monitor.request(planId, DeckStrategyPlanner.INSTRUCTIONS, deck); }
            status(planId, "Preparing deck strategy (or loading the matching cached plan)…");
            try {
                plan = DeckStrategyPlanner.planInput(deck, directory.resolve("live-deck-plans"), provider, model, effort, timeout, 8192);
                plans.put(deckHash, plan);
                if (monitor != null) { monitor.response(planId, plan); }
                diagnostic("Deck preparation", "Ready (generated or cached)");
                status(planId, "Deck strategy ready for LLM decisions.");
            } catch (IOException | RuntimeException e) {
                diagnostic("Deck preparation", Thread.currentThread().isInterrupted() ? "Cancelled" : "Failed; continuing without plan");
                if (Thread.currentThread().isInterrupted()) { throw new IOException("Deck planning interrupted.", e); }
                JsonObject error = new JsonObject();
                error.addProperty("status", "deck_plan_failed");
                error.addProperty("error", e.getMessage());
                error.addProperty("continuingWithoutPlan", true);
                if (monitor != null) { monitor.response(planId, error); }
                status(planId, "Deck strategy unavailable; continuing LLM play without a deck plan.");
            } finally {
                diagnostics.time("Deck preparation (including cache lookup)", (System.nanoTime() - started) / 1_000_000);
                publishInsights(true);
            }
        }
        return plan;
    }

    private String review(String id, JsonObject question, JsonObject deck, String deckHash, long queued, String kind,
                          String memoryKey, int turn) throws IOException {
        long queueMillis = (System.nanoTime() - queued) / 1_000_000;
        diagnostics.time("Worker queue", queueMillis);
        JsonObject plan = preparePlan(id, deck, deckHash);
        memory.attach(question, memoryKey, turn);
        JsonObject input = plan == null ? question : DeckStrategyPlanner.attach(question, plan, deckHash);
        if (monitor != null) { monitor.request(id, ProviderClient.INSTRUCTIONS, input); }
        status(id, "Luna is choosing a move… Forge's display remains available.");
        JsonObject report = ShadowReview.review(provider, model, input, timeout, effort, 8192);
        report.addProperty("queueMillis", queueMillis);
        diagnostic("Model replies — " + kind, report.get("status").getAsString());
        diagnostics.time("Model reply — " + kind, report.get("elapsedMillis").getAsLong());
        report.addProperty("mode", "live_hybrid");
        report.add("input", input);
        if (monitor != null) { monitor.response(id, report); }
        PrivateFiles.write(session.resolve(id + ".json"), JSON.toJson(report));
        if (!"legal_recommendation".equals(report.get("status").getAsString())) {
            status(id, "Forge fallback: " + report.get("status").getAsString() + "; " + report.get("error").getAsString());
            throw new IOException("Model request did not produce a legal recommendation.");
        }
        if (report.has("strategyNotes")) {
            memory.propose(memoryKey, id, turn, report.get("strategyNotes").getAsString());
        }
        status(id, "Received in " + report.get("elapsedMillis") + " ms: " + report.get("reason").getAsString());
        return report.get("recommendedActionId").getAsString();
    }

    @Override public void status(String id, String message) {
        if (monitor != null) { monitor.status(id, message); }
        publishInsights(true);
    }
}
