package forge.llm;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveHybridTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }

    private Player position(Game game) {
        Player player = game.getPhaseHandler().getPlayerTurn();
        player.setLife(20, null);
        for (Player other : game.getPlayers()) { other.setLife(20, null); }
        addCard("Thespian's Stage", player);
        addCard("Island", player);
        addCard("Island", player);
        addCardToZone("Dark Depths", player, ZoneType.Hand);
        addCardToZone("Swamp", player, ZoneType.Hand);
        return player;
    }

    private void advisor(java.util.function.Function<JsonObject, String> policy) {
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject question) { return policy.apply(question); }
            @Override public void status(String id, String message) { }
        });
    }

    private String select(JsonObject input, String description) {
        return input.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsJsonObject())
                .filter(e -> e.get("description").getAsString().contains(description))
                .findFirst().orElseThrow().get("actionId").getAsString();
    }

    public void liveChoiceActuallyPlaysALandAndThenCopiesItWithRealPayment() {
        Game game = initAndCreateGame();
        Player player = position(game);
        advisor(q -> select(q, "Play Dark Depths"));
        List<SpellAbility> land = LiveDecisionBridge.choose(player, null);
        assertNotNull(land);
        assertEquals(land.get(0).getHostCard().getName(), "Dark Depths");
        player.getController().playChosenSpellAbility(land.get(0));
        assertEquals(countCardsWithName(game, "Dark Depths"), 1);
        assertEquals(player.getLandsPlayedThisTurn(), 1);
        advisor(q -> q.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsJsonObject())
                .filter(e -> e.get("description").getAsString().contains("Dark Depths")
                        && e.get("description").getAsString().contains("target:"))
                .findFirst().orElseThrow().get("actionId").getAsString());
        List<SpellAbility> copy = LiveDecisionBridge.choose(player, null);
        assertNotNull(copy, "target validation must not reject a selected target as a duplicate");
        player.getController().playChosenSpellAbility(copy.get(0));
        assertEquals(game.getStack().size(), 1);
        assertTrue(findCardWithName(game, "Thespian's Stage").isTapped());
        assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Island") && c.isTapped()).count(), 2L);
    }

    public void malformedAndUnavailableMovesKeepTheExactBaseline() {
        Game game = initAndCreateGame();
        Player player = position(game);
        List<SpellAbility> baseline = List.of(player.getCardsIn(ZoneType.Hand).get(1).getFirstSpellAbility());
        baseline.get(0).setActivatingPlayer(player);
        advisor(q -> "invented-id");
        assertSame(LiveDecisionBridge.choose(player, baseline), baseline);
        advisor(q -> {
            String selected = select(q, "Play Dark Depths");
            player.setLandsPlayedThisTurn(1);
            return selected;
        });
        assertSame(LiveDecisionBridge.choose(player, baseline), baseline);
        assertEquals(player.getCardsIn(ZoneType.Hand).size(), 2);
    }

    public void noAdapterAndSimulationCopiesNeverRequestAModel() {
        Game game = initAndCreateGame();
        Player player = position(game);
        assertNull(LiveDecisionBridge.choose(player, null));
        AtomicInteger calls = new AtomicInteger();
        advisor(q -> { calls.incrementAndGet(); return "pass"; });
        Game copy = new GameCopier(game).makeCopy();
        assertNull(LiveDecisionBridge.choose(copy.getPhaseHandler().getPlayerTurn(), null));
        assertEquals(calls.get(), 0);
    }

    public void hiddenHumanCardsAndLibrariesAreAbsentFromLiveInput() {
        Game game = initAndCreateGame();
        Player player = position(game);
        Player other = game.getPlayers().get(0);
        addCardToZone("Wrath of God", other, ZoneType.Hand);
        addCardToZone("Black Lotus", other, ZoneType.Library);
        advisor(q -> {
            assertFalse(q.toString().contains("Wrath of God"));
            assertFalse(q.toString().contains("Black Lotus"));
            return "pass";
        });
        assertNull(LiveDecisionBridge.choose(player, null));
    }

    public void liveProviderPathPlansOnceRecordsExactInputsAndCanBePaused() throws Exception {
        assertLivePlanResponse("{\"strategy\":\"Assemble the combo while preserving mana.\",\"cautions\":\"Current legality takes precedence.\"}", true);
    }

    public void overSuggestedLengthDeckPlanStillReachesMoveRequests() throws Exception {
        JsonObject plan = new JsonObject();
        plan.addProperty("strategy", "Conditional strategy. ".repeat(200));
        plan.addProperty("cautions", "Respect the board. ".repeat(60));
        assertLivePlanResponse(plan.toString(), true);
    }

    public void malformedDeckPlanDoesNotBlockOrRetryDuringLaterMoves() throws Exception {
        assertLivePlanResponse("not a JSON plan", false);
    }

    public void excessiveDeckPlanDoesNotBlockOrRetryDuringLaterMoves() throws Exception {
        JsonObject plan = new JsonObject();
        plan.addProperty("strategy", "x".repeat(DeckStrategyPlanner.MAX_PLAN_CHARACTERS));
        plan.addProperty("cautions", "Still too long.");
        assertLivePlanResponse(plan.toString(), false);
    }

    public void failedPregamePlanStillAllowsMovesWithoutRetrying() throws Exception {
        assertLivePlanResponse("not a JSON plan", false, true);
    }

    public void pregamePlanningDoesNotBlockSetupAndIsReusedByTheFirstMove() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger plans = new AtomicInteger(), reviews = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String instructions = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            JsonObject input = LlmHttp.object(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
            String response;
            if (instructions.contains("pregame strategy")) {
                plans.incrementAndGet();
                assertTrue(input.has("mainDeck"));
                assertFalse(input.has("players")); assertFalse(input.has("hand")); assertFalse(input.has("libraryOrder"));
                started.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); exchange.close(); return; }
                response = "{\"strategy\":\"Preserve resources.\",\"cautions\":\"Use the actual board.\"}";
            } else {
                reviews.incrementAndGet();
                assertTrue(input.has("deckStrategy"));
                response = "{\"actionId\":\"pass\",\"reason\":\"Wait for this test.\"}";
            }
            JsonObject message = new JsonObject(); message.addProperty("content", response);
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
        try (LiveAdvisor launcher = new LiveAdvisor(provider, "model", "high", Duration.ofSeconds(5),
                Files.createTempDirectory(Path.of("target"), "live-prefetch-"))) {
            LiveDecisionBridge.install(launcher);
            Game game = initAndCreateGame(); Player player = position(game);
            game.setAge(GameStage.BeforeMulligan);
            // The controller must return while the provider is deliberately held open.
            CompletableFuture.runAsync(() -> player.getController().prepareForGame()).get(2, TimeUnit.SECONDS);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(game.getAge(), GameStage.BeforeMulligan); assertEquals(reviews.get(), 0);
            player.getController().prepareForGame(); // Duplicate startup notification must reuse the same plan.
            release.countDown();
            game.setAge(GameStage.Play);
            assertNull(LiveDecisionBridge.choose(player, null));
            assertEquals(plans.get(), 1); assertEquals(reviews.get(), 1);
        } finally { release.countDown(); server.stop(0); }
    }

    private void assertLivePlanResponse(String planResponse, boolean withPlan) throws Exception {
        assertLivePlanResponse(planResponse, withPlan, false);
    }

    private void assertLivePlanResponse(String planResponse, boolean withPlan, boolean prefetch) throws Exception {
        AtomicInteger plans = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String instructions = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String response;
            if (instructions.contains("pregame strategy")) {
                plans.incrementAndGet();
                response = planResponse;
            } else {
                reviews.incrementAndGet();
                JsonObject state = LlmHttp.object(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                assertEquals(state.has("deckStrategy"), withPlan);
                response = "{\"actionId\":\"pass\",\"reason\":\"Wait for this test.\"}";
            }
            JsonObject message = new JsonObject();
            message.addProperty("content", response);
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path directory = Files.createTempDirectory(Path.of("target"), "live-hybrid-");
            ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
            LivePlayLauncher launcher = new LivePlayLauncher(provider, "model", "high", Duration.ofSeconds(3), directory);
            AtomicReference<String> displayedInsights = new AtomicReference<>();
            launcher.setMonitor(new LiveAdvisor.Monitor() {
                @Override public void request(String id, String instructions, JsonObject input) { }
                @Override public void response(String id, JsonObject output) { }
                @Override public void status(String id, String message) { }
                @Override public void insights(String report) { displayedInsights.set(report); }
            });
            LiveDecisionBridge.install(launcher);
            Game game = initAndCreateGame();
            Player player = position(game);
            if (prefetch) {
                game.setAge(GameStage.BeforeMulligan);
                player.getController().prepareForGame();
                game.setAge(GameStage.Play);
            }
            List<SpellAbility> baseline = List.of(player.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility().copy(player));
            for (int i = 0; i < 4; i++) { assertNull(LiveDecisionBridge.choose(player, baseline)); }
            assertEquals(plans.get(), 1);
            assertEquals(reviews.get(), 4);
            assertTrue(launcher.isEnabled(), "A failed deck plan must not count as failed move requests.");
            assertTrue(displayedInsights.get().contains("Game wait — Action selection: 4 samples"));
            assertTrue(displayedInsights.get().contains("Model reply — Action selection: 4 samples"));
            assertTrue(displayedInsights.get().contains("legal_recommendation: 4"));
            assertFalse(displayedInsights.get().contains("test-key"));
            try (var files = Files.walk(directory.resolve("live"))) {
                List<Path> reports = files.filter(p -> p.toString().endsWith(".json")).toList();
                assertEquals(reports.size(), 4);
                JsonObject report = LlmHttp.object(Files.readString(reports.get(0)));
                assertEquals(report.get("mode").getAsString(), "live_hybrid");
                assertEquals(report.getAsJsonObject("input").has("deckStrategy"), withPlan);
                assertFalse(report.toString().contains("test-key"));
                assertTrue(report.get("queueMillis").getAsLong() >= 0);
            }
            launcher.pause();
            assertNull(LiveDecisionBridge.choose(player, null));
            assertEquals(reviews.get(), 4);
            launcher.close();
        } finally { server.stop(0); }
    }

    public void notesReachLaterRequestsOnlyAfterEngineAcceptanceAndCanBeCleared() throws Exception {
        List<JsonObject> inputs = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String instructions = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            JsonObject answer = new JsonObject();
            if (instructions.contains("pregame strategy")) {
                answer.addProperty("strategy", "Assemble the combo."); answer.addProperty("cautions", "Respect the board.");
            } else {
                JsonObject input = LlmHttp.object(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                inputs.add(input);
                answer.addProperty("actionId", inputs.size() <= 2 ? select(input, "Play Dark Depths") : "pass");
                answer.addProperty("reason", "Preserve resources.");
                answer.addProperty("strategyNotes", inputs.size() == 1 ? "Rejected intent." : inputs.size() == 2 ? "Keep protection available." : "");
            }
            JsonObject message = new JsonObject(); message.addProperty("content", answer.toString());
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
        try (LiveAdvisor launcher = new LiveAdvisor(provider, "model", "high", Duration.ofSeconds(3),
                Files.createTempDirectory(Path.of("target"), "live-notes-"))) {
            LiveDecisionBridge.install(launcher); Player player = position(initAndCreateGame());
            List<SpellAbility> rejected = LiveDecisionBridge.choose(player, null); assertNotNull(rejected);
            LiveDecisionBridge.executed(player, rejected.get(0), false);
            List<SpellAbility> accepted = LiveDecisionBridge.choose(player, null); assertNotNull(accepted);
            player.getController().playChosenSpellAbility(accepted.get(0));
            SpellAbility copy = findCardWithName(player.getGame(), "Thespian's Stage").getSpellAbilities().stream()
                    .filter(sa -> sa.getApi() == forge.game.ability.ApiType.Clone).findFirst().orElseThrow().copy(player);
            copy.getTargets().add(findCardWithName(player.getGame(), "Dark Depths"));
            assertNull(LiveDecisionBridge.choose(player, List.of(copy))); assertNull(LiveDecisionBridge.choose(player, List.of(copy)));
            assertEquals(inputs.size(), 4);
            assertFalse(inputs.get(0).has("strategyMemory")); assertFalse(inputs.get(1).has("strategyMemory"));
            assertEquals(inputs.get(2).getAsJsonObject("strategyMemory").get("notes").getAsString(), "Keep protection available.");
            assertFalse(inputs.get(2).toString().contains("Rejected intent."));
            assertFalse(inputs.get(3).has("strategyMemory"));
        } finally { server.stop(0); }
    }

    public void quietPolicySkipsNetworkCallsButStillReviewsForgeActionsAndNextTurn() throws Exception {
        AtomicInteger reviews = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean plan = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("pregame strategy");
            String answer = "{\"strategy\":\"Play aggressively.\",\"cautions\":\"Respect legality.\"}";
            if (!plan) {
                reviews.incrementAndGet();
                JsonObject input = LlmHttp.object(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                assertTrue(input.get("consultationPolicyRequested").getAsBoolean());
                answer = "{\"actionId\":\"pass\",\"reason\":\"Save resources.\",\"consultationPolicy\":{\"watch\":[]}}";
            }
            JsonObject message = new JsonObject(); message.addProperty("content", answer);
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
        try (LiveAdvisor launcher = new LiveAdvisor(provider, "model", "high", Duration.ofSeconds(3),
                Files.createTempDirectory(Path.of("target"), "live-consultation-"))) {
            LiveDecisionBridge.install(launcher); Game game = initAndCreateGame(); Player player = position(game);
            assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(reviews.get(), 1);
            assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(reviews.get(), 1);
            game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN2, player, 1);
            assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(reviews.get(), 1);
            Player opponent = game.getPlayers().stream().filter(p -> p != player).findFirst().orElseThrow();
            game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.END_OF_TURN, opponent, 2);
            assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(reviews.get(), 1);
            game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, player, 3);
            assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(reviews.get(), 2);
            List<SpellAbility> baseline = List.of(player.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility().copy(player));
            assertNull(LiveDecisionBridge.choose(player, baseline)); assertEquals(reviews.get(), 3);
            assertTrue(launcher.insights().contains("Skipped: Forge passes"));
            assertTrue(launcher.insights().contains("Updated after accepted choice"));
        } finally { server.stop(0); }
    }

    public void realCounterspellCandidateTriggersARequestedReactiveWindow() throws Exception {
        Game game = initAndCreateGame(); Player player = position(game);
        addCardToZone("Counterspell", player, ZoneType.Hand);
        ConsultationScheduler scheduler = new ConsultationScheduler(); AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> reason = new AtomicReference<>();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public boolean shouldReview(Player p, JsonObject q) throws Exception {
                var verdict = scheduler.consider("control", q); reason.set(verdict.reason()); return verdict.consult();
            }
            @Override public String choose(Player p, JsonObject q) throws Exception {
                calls.incrementAndGet(); scheduler.register("control", q);
                scheduler.propose(q.get("decisionId").getAsString(), LlmHttp.object("{\"watch\":[\"counterspell_window\"]}"));
                if (calls.get() == 2) {
                    assertTrue(q.getAsJsonArray("candidates").asList().stream().anyMatch(c -> c.getAsJsonObject().has("effect")
                            && c.getAsJsonObject().get("effect").getAsString().equals("Counter")));
                    assertTrue(q.getAsJsonArray("stack").get(0).getAsJsonObject().get("isSpell").getAsBoolean());
                }
                return "pass";
            }
            @Override public void completed(String id, boolean accepted) { scheduler.complete(id, accepted); }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(calls.get(), 1);
        Player opponent = game.getPlayers().stream().filter(p -> p != player).findFirst().orElseThrow();
        game.getPhaseHandler().devModeSet(forge.game.phase.PhaseType.MAIN1, opponent, 2);
        var creature = addCardToZone("Grizzly Bears", opponent, ZoneType.Hand);
        game.getStack().add(creature.getFirstSpellAbility().copy(opponent)); game.getPhaseHandler().setPriority(player);
        assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(calls.get(), 2);
        assertEquals(reason.get(), "Requested counterspell window with a legal counter");
    }

    public void providerInvalidMoveGetsOneCorrectionAndCanRecover() throws Exception { assertProviderCorrection(true); }
    public void providerInvalidMovesStopAfterTheSecondAttempt() throws Exception { assertProviderCorrection(false); }

    private void assertProviderCorrection(boolean recover) throws Exception {
        List<JsonObject> inputs = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean plan = request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("pregame strategy");
            String answer = "{\"strategy\":\"Sequence resources.\",\"cautions\":\"Check current legality.\"}";
            if (!plan) {
                JsonObject input = LlmHttp.object(request.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
                inputs.add(input);
                if (inputs.size() == 2) {
                    assertTrue(input.has("correction")); assertFalse(input.has("strategyMemory"));
                }
                answer = "{\"actionId\":\"" + (recover && inputs.size() == 2 ? "pass" : "invented")
                        + "\",\"reason\":\"Test correction.\",\"strategyNotes\":\"Rejected notes must never persist.\"}";
            }
            JsonObject message = new JsonObject(); message.addProperty("content", answer);
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
        try (LiveAdvisor launcher = new LiveAdvisor(provider, "model", "high", Duration.ofSeconds(3),
                Files.createTempDirectory(Path.of("target"), "live-correction-"))) {
            LiveDecisionBridge.install(launcher); Player player = position(initAndCreateGame());
            List<SpellAbility> baseline = List.of(player.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility().copy(player));
            var chosen = LiveDecisionBridge.choose(player, baseline);
            if (recover) { assertNull(chosen); } else { assertSame(chosen, baseline); }
            assertEquals(inputs.size(), 2); assertNotEquals(inputs.get(0).get("decisionId"), inputs.get(1).get("decisionId"));
            assertTrue(launcher.isEnabled()); assertTrue(launcher.insights().contains("Corrective retries"));
        } finally { server.stop(0); }
    }

    public void repeatedProviderFailuresPauseLiveRequestsAndKeepForgePlaying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
            LivePlayLauncher launcher = new LivePlayLauncher(provider, "model", "high", Duration.ofSeconds(1),
                    Files.createTempDirectory(Path.of("target"), "live-failure-"));
            LiveDecisionBridge.install(launcher);
            Player player = position(initAndCreateGame());
            for (int i = 0; i < 4; i++) { assertNull(LiveDecisionBridge.choose(player, null)); }
            assertEquals(calls.get(), 4, "One deck-plan attempt plus three failed moves; the fourth move must stay paused.");
            assertTrue(launcher.insights().contains("provider_error: 3"));
            assertTrue(launcher.insights().contains("Failed; continuing without plan: 1"));
        } finally { server.stop(0); }
    }
}
