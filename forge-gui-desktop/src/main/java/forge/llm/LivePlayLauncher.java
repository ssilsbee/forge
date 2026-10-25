package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import forge.game.player.Player;

import javax.swing.SwingUtilities;
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

/** Explicit alternate desktop entry point: ordinary Forge startup never installs the live bridge. */
public final class LivePlayLauncher implements LiveDecisionBridge.Advisor {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static LivePlayLauncher active;
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
    private final Map<String, JsonObject> plans = new HashMap<>(); // worker only
    private final Map<Player, Budget> budgets = new WeakHashMap<>(); // game thread only
    private volatile Future<?> pending;
    private LlmMonitor monitor;
    private final AtomicInteger failures = new AtomicInteger();
    private static final class Budget { int turn = -1; int count; int total; }

    LivePlayLauncher(ProviderClient provider, String model, String effort, Duration timeout, Path directory) {
        this.provider = provider;
        this.model = model;
        this.effort = effort;
        this.timeout = timeout;
        this.directory = directory;
        session = directory.resolve("live").resolve("session-" + Instant.now().toEpochMilli());
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(System.getProperty("forge.llm.dataDir"));
        String connection = System.getProperty("forge.llm.provider", "chatgpt");
        ProviderClient provider;
        if (connection.equals("chatgpt")) {
            provider = ProviderClient.chatGpt(new ChatGptAuth(directory.resolve("connections")), System.getProperty("forge.llm.profile"));
        } else if (connection.equals("openrouter")) { provider = ProviderClient.openRouter(System.getenv("OPENROUTER_API_KEY")); }
        else { throw new IOException("Unsupported live provider."); }
        int seconds = Integer.getInteger("forge.llm.timeoutSeconds", 45);
        if (seconds < 1 || seconds > 300) { throw new IOException("Timeout must be between 1 and 300 seconds."); }
        active = new LivePlayLauncher(provider, System.getProperty("forge.llm.model", "gpt-6-luna"),
                System.getProperty("forge.llm.reasoningEffort", "high"), Duration.ofSeconds(seconds), directory);
        SwingUtilities.invokeAndWait(() -> active.monitor = new LlmMonitor(active.model, active.effort, active::pause));
        LiveDecisionBridge.install(active);
        forge.view.Main.main(new String[0]);
    }

    static void resume() {
        if (active != null) {
            active.failures.set(0);
            active.enabled.set(true);
            active.status("", "Luna enabled for supported decisions. Forge handles combat and other resolution choices.");
        }
    }

    void pause() {
        enabled.set(false);
        Future<?> task = pending;
        if (task != null) { task.cancel(true); }
        status("", "Luna paused. Forge is in control. Enable Luna to resume.");
    }

    @Override public String choose(Player player, JsonObject question) throws Exception {
        if (!enabled.get()) { throw new IOException("Luna is paused."); }
        if (SwingUtilities.isEventDispatchThread()) { throw new IOException("Never wait for a model on the display thread."); }
        Budget budget = budgets.computeIfAbsent(player, p -> new Budget());
        int turn = player.getGame().getPhaseHandler().getTurn();
        if (budget.turn != turn) { budget.turn = turn; budget.count = 0; }
        if (++budget.count > 20 || ++budget.total > 150) { throw new IOException("Live request budget reached; Forge continues."); }
        // Freeze all game-derived data before dispatch. No live player/card/deck object reaches the worker.
        JsonObject deck = DeckStrategyPlanner.deckInput(player.getRegisteredPlayer().getDeck());
        String deckHash = DecisionBenchmark.digest(deck.toString());
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
        if (plan == null) {
            String planId = id + " / deck plan";
            if (monitor != null) { monitor.request(planId, DeckStrategyPlanner.INSTRUCTIONS, deck); }
            status(planId, "Preparing deck strategy (or loading the matching cached plan)…");
            try {
                plan = DeckStrategyPlanner.planInput(deck, directory.resolve("live-deck-plans"), provider, model, effort, timeout, 8192);
                plans.put(deckHash, plan);
                if (monitor != null) { monitor.response(planId, plan); }
            } catch (IOException e) {
                JsonObject error = new JsonObject();
                error.addProperty("status", "deck_plan_failed");
                error.addProperty("error", e.getMessage());
                if (monitor != null) { monitor.response(planId, error); }
                throw e;
            }
        }
        JsonObject input = DeckStrategyPlanner.attach(question, plan, deckHash);
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
