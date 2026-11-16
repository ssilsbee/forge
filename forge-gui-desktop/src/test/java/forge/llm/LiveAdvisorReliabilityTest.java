package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.LandDecisionSnapshot;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckBase;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.testng.annotations.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveAdvisorReliabilityTest extends AITest {
    private static class BlockingDeck extends Deck {
        volatile boolean armed;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override protected DeckBase newInstance(String name) { return new BlockingDeck(); }
        @Override public CardPool getMain() {
            if (armed) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Snapshot test timed out"); } }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
            return super.getMain();
        }
    }
    private Player player() {
        var registered = List.of(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("other", null)),
                new RegisteredPlayer(new BlockingDeck()).setPlayer(new LobbyPlayerAi("advisor", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(registered, rules, new Match(rules, registered, "Reliability"));
        Player player = game.getPlayers().get(1);
        game.setAge(GameStage.Play); game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player);
        return player;
    }
    private JsonObject question(Player player) {
        JsonObject q = LandDecisionSnapshot.visibleState(player);
        q.addProperty("schemaVersion", 1); q.addProperty("decisionType", "live_action");
        q.addProperty("decisionId", UUID.randomUUID().toString()); q.addProperty("activePlayer", "p1");
        q.addProperty("baselineActionId", "pass");
        JsonArray candidates = new JsonArray();
        for (String id : List.of("pass", "other")) { JsonObject c = new JsonObject(); c.addProperty("actionId", id); candidates.add(c); }
        q.add("candidates", candidates); return q;
    }
    private static class Provider implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final HttpServer server;
        Provider() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                calls.incrementAndGet();
                JsonObject body = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                boolean plan = body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("pregame strategy");
                JsonObject message = new JsonObject();
                message.addProperty("content", plan ? "{\"strategy\":\"Wait.\",\"cautions\":\"Check legality.\"}" : "{\"actionId\":\"pass\",\"reason\":\"Wait.\"}");
                byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            server.start();
        }
        ProviderClient client() {
            return new ProviderClient(ProviderClient.Provider.OPENROUTER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "fake-test-key");
        }
        @Override public void close() { server.stop(0); }
    }
    public void pauseDuringSnapshotPreventsSubmissionEvenAfterResume() throws Exception {
        for (boolean resume : List.of(false, true)) {
            Player player = player(); BlockingDeck deck = (BlockingDeck) player.getRegisteredPlayer().getDeck();
            JsonObject q = question(player);
            try (Provider provider = new Provider();
                 LiveAdvisor advisor = new LiveAdvisor(provider.client(), "test", "high", Duration.ofSeconds(3), Files.createTempDirectory("pause-review"))) {
                deck.armed = true;
                CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
                    try { return advisor.choose(player, q); } catch (Exception e) { return e.getClass().getSimpleName(); }
                });
                try {
                    assertTrue(deck.entered.await(3, TimeUnit.SECONDS));
                    advisor.pause(); if (resume) { advisor.resumeLive(); }
                } finally { deck.release.countDown(); }
                assertEquals(result.get(3, TimeUnit.SECONDS), "IOException");
                assertEquals(provider.calls.get(), 0);
                deck.armed = false; advisor.resumeLive();
                assertEquals(advisor.choose(player, question(player)), "pass");
                assertEquals(provider.calls.get(), 2, "Only the new decision may plan and call the provider");
            }
        }
    }
    public void unwritableReportsDoNotDiscardLegalDecisionsOrTripCircuitBreaker() throws Exception {
        Player player = player(); Path directory = Files.createTempDirectory("report-storage-review");
        Files.createFile(directory.resolve("live")); // Deterministic failure on Windows and Unix, including elevated runs.
        try (Provider provider = new Provider();
             LiveAdvisor advisor = new LiveAdvisor(provider.client(), "test", "high", Duration.ofSeconds(3), directory)) {
            for (int i = 0; i < 4; i++) { assertEquals(advisor.choose(player, question(player)), "pass"); }
            assertTrue(advisor.isEnabled());
            assertEquals(provider.calls.get(), 5);
            assertTrue(advisor.insights().contains("Could not save decision report: 4"));
        }
    }

    public void cancelledPreparationCanBeRetriedAfterResume() throws Exception {
        Player player = player(); CountDownLatch cancelled = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (Provider provider = new Provider();
             LiveAdvisor advisor = new LiveAdvisor(provider.client(), "test", "high", Duration.ofSeconds(3), Files.createTempDirectory("prepare-cancel-review"))) {
            advisor.setMonitor(new LiveAdvisor.Monitor() {
                @Override public void request(String id, String instructions, JsonObject input) {
                    if (id.endsWith(" / deck plan") && first.compareAndSet(true, false)) {
                        advisor.pause(); cancelled.countDown();
                    }
                }
                @Override public void response(String id, JsonObject output) { }
                @Override public void status(String id, String message) { }
            });
            advisor.prepare(player);
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            advisor.resumeLive();
            assertEquals(advisor.choose(player, question(player)), "pass");
            assertEquals(provider.calls.get(), 2, "Cancelled preparation must not poison the deck-plan cache");
        }
    }
}
