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
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private final Map<Player, Budget> budgets = new WeakHashMap<>(); // game thread only
    private volatile Future<?> pending;
    private volatile Monitor monitor;
    private final AtomicInteger failures = new AtomicInteger();
    private static final class Budget { int turn = -1; int count; int choices; int total; }

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
    }

    public void setMonitor(Monitor value) { monitor = value; }
    @Override public boolean isEnabled() { return enabled.get(); }
    public void resumeLive() {
        failures.set(0);
        enabled.set(true);
        status("", "LLM enabled for supported actions, attacks, blocks, card choices, scry and surveil.");
    }

    @Override public void close() { pause(); worker.shutdownNow(); }

    public void pause() {
        enabled.set(false);
        Future<?> task = pending;
        if (task != null) { task.cancel(true); }
        status("", "Luna paused. Forge is in control. Enable Luna to resume.");
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
        Future<String> task = worker.submit(() -> review(id, detached, deck, deckHash));
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
        } finally { pending = null; }
    }

    private String review(String id, JsonObject question, JsonObject deck, String deckHash) throws IOException {
        JsonObject plan = plans.get(deckHash);
        if (!plans.containsKey(deckHash)) {
            plans.put(deckHash, null);
            String planId = id + " / deck plan";
            if (monitor != null) { monitor.request(planId, DeckStrategyPlanner.INSTRUCTIONS, deck); }
            status(planId, "Preparing deck strategy (or loading the matching cached plan)…");
            try {
                plan = DeckStrategyPlanner.planInput(deck, directory.resolve("live-deck-plans"), provider, model, effort, timeout, 8192);
                plans.put(deckHash, plan);
                if (monitor != null) { monitor.response(planId, plan); }
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) { throw new IOException("Deck planning interrupted.", e); }
                JsonObject error = new JsonObject();
                error.addProperty("status", "deck_plan_failed");
                error.addProperty("error", e.getMessage());
                error.addProperty("continuingWithoutPlan", true);
                if (monitor != null) { monitor.response(planId, error); }
                status(planId, "Deck strategy unavailable; continuing LLM play without a deck plan.");
            }
        }
        JsonObject input = plan == null ? question : DeckStrategyPlanner.attach(question, plan, deckHash);
        if (monitor != null) { monitor.request(id, ProviderClient.INSTRUCTIONS, input); }
        status(id, "Luna is choosing a move… Forge's display remains available.");
        JsonObject report = ShadowReview.review(provider, model, input, timeout, effort, 8192);
        report.addProperty("mode", "live_hybrid");
        report.add("input", input);
        if (monitor != null) { monitor.response(id, report); }
        PrivateFiles.write(session.resolve(id + ".json"), JSON.toJson(report));
        if (!"legal_recommendation".equals(report.get("status").getAsString())) {
            status(id, "Forge fallback: " + report.get("status").getAsString() + "; " + report.get("error").getAsString());
            throw new IOException("Model request did not produce a legal recommendation.");
        }
        status(id, "Received in " + report.get("elapsedMillis") + " ms: " + report.get("reason").getAsString());
        return report.get("recommendedActionId").getAsString();
    }

    @Override public void status(String id, String message) {
        if (monitor != null) { monitor.status(id, message); }
    }
}
