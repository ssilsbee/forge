package forge.llm;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    private void assertLivePlanResponse(String planResponse, boolean withPlan) throws Exception {
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
            LiveDecisionBridge.install(launcher);
            Game game = initAndCreateGame();
            Player player = position(game);
            for (int i = 0; i < 4; i++) { assertNull(LiveDecisionBridge.choose(player, null)); }
            assertEquals(plans.get(), 1);
            assertEquals(reviews.get(), 4);
            assertTrue(launcher.isEnabled(), "A failed deck plan must not count as failed move requests.");
            try (var files = Files.walk(directory.resolve("live"))) {
                List<Path> reports = files.filter(p -> p.toString().endsWith(".json")).toList();
                assertEquals(reports.size(), 4);
                JsonObject report = LlmHttp.object(Files.readString(reports.get(0)));
                assertEquals(report.get("mode").getAsString(), "live_hybrid");
                assertEquals(report.getAsJsonObject("input").has("deckStrategy"), withPlan);
                assertFalse(report.toString().contains("test-key"));
            }
            launcher.pause();
            assertNull(LiveDecisionBridge.choose(player, null));
            assertEquals(reviews.get(), 4);
            launcher.close();
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
        } finally { server.stop(0); }
    }
}
