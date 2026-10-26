package forge.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.BeforeClass;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class TurnSequenceExperimentTest {
    @BeforeClass public void initialize() { LandDecisionExample.initialize(); }

    static String sensibleChoice(JsonObject question) {
        var choices = question.getAsJsonArray("candidates");
        if (question.get("scope").getAsString().startsWith("Legend rule")) {
            for (JsonElement player : question.getAsJsonArray("players")) {
                if (!player.getAsJsonObject().get("self").getAsBoolean()) { continue; }
                for (JsonElement element : player.getAsJsonObject().getAsJsonArray("Battlefield")) {
                    JsonObject card = element.getAsJsonObject();
                    if (card.get("name").getAsString().equals("Dark Depths") && card.getAsJsonObject("counters").size() == 0) {
                        return "keep-" + card.get("id").getAsString().substring(5);
                    }
                }
            }
        }
        for (JsonElement choice : choices) {
            if (choice.getAsJsonObject().get("description").getAsString().startsWith("Cast Dispel")) {
                return choice.getAsJsonObject().get("actionId").getAsString();
            }
        }
        boolean karakas = question.getAsJsonArray("players").asList().stream().anyMatch(p ->
                !p.getAsJsonObject().get("self").getAsBoolean() && p.getAsJsonObject().getAsJsonArray("Battlefield").asList()
                        .stream().anyMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("Karakas")));
        if (karakas) { return "pass"; }
        for (JsonElement choice : choices) {
            if (choice.getAsJsonObject().get("description").getAsString().equals("Play Dark Depths")) {
                return choice.getAsJsonObject().get("actionId").getAsString();
            }
        }
        String depths = null;
        for (JsonElement player : question.getAsJsonArray("players")) {
            if (!player.getAsJsonObject().get("self").getAsBoolean()) { continue; }
            for (JsonElement element : player.getAsJsonObject().getAsJsonArray("Battlefield")) {
                if (element.getAsJsonObject().get("name").getAsString().equals("Dark Depths")) {
                    depths = element.getAsJsonObject().get("id").getAsString();
                }
            }
        }
        for (JsonElement choice : choices) {
            String description = choice.getAsJsonObject().get("description").getAsString();
            if (depths != null && (description.startsWith("Activate Thespian's Stage") || description.startsWith("Activate Vampire Hexmage"))
                    && description.endsWith("target " + depths)) {
                return choice.getAsJsonObject().get("actionId").getAsString();
            }
        }
        return "pass";
    }

    public void completeEngineTurnsExerciseBothCombosAndActualOpponentResponses() throws IOException {
        for (String id : TurnSequenceExperiment.scenarioIds()) {
            JsonObject result = TurnSequenceExperiment.simulate(id, TurnSequenceExperiment.Mode.WITHOUT_PLAN,
                    null, TurnSequenceExperimentTest::sensibleChoice);
            assertTrue(result.get("fullTurnCompleted").getAsBoolean(), id);
            if (!id.equals("hexmage-hidden-stifle")) { assertTrue(result.get("objectivePassed").getAsBoolean(), id + ": " + result); }
            if (id.contains("protected")) {
                assertTrue(result.get("opponentResponseExecuted").getAsBoolean(), id);
                assertTrue(result.get("dispelCast").getAsBoolean(), id);
            }
            if (id.equals("hexmage-hidden-stifle")) {
                assertTrue(result.get("opponentResponseExecuted").getAsBoolean());
                assertFalse(result.get("tokenAtEnd").getAsBoolean());
                assertFalse(result.get("scored").getAsBoolean());
            }
            if (id.equals("stage-clean")) {
                assertTrue(result.getAsJsonArray("decisionTrace").asList().stream().anyMatch(d ->
                        d.getAsJsonObject().getAsJsonObject("question").get("scope").getAsString().startsWith("Legend rule")));
            }
        }
    }

    public void opponentAnswerAppearsOnlyAfterItIsPublicAndDeckCountsAreConsistent() throws IOException {
        List<JsonObject> questions = new ArrayList<>();
        TurnSequenceExperiment.simulate("stage-stifle-protected", TurnSequenceExperiment.Mode.WITHOUT_PLAN, null,
                q -> { questions.add(q.deepCopy()); return sensibleChoice(q); });
        assertFalse(questions.get(0).toString().contains("Stifle"));
        assertTrue(questions.stream().anyMatch(q -> q.getAsJsonArray("stack").toString().contains("Stifle")));
        assertTrue(questions.stream().noneMatch(q -> q.has("deckStrategy")));
        JsonObject self = questions.get(0).getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(self.get("handCount").getAsInt() + self.get("libraryCount").getAsInt() + self.getAsJsonArray("Battlefield").size(), 60);
        assertFalse(questions.get(0).getAsJsonArray("players").get(1).getAsJsonObject().has("hand"));
    }

    public void invalidActionCannotReachTheEngine() {
        assertThrows(IOException.class, () -> TurnSequenceExperiment.simulate("stage-clean",
                TurnSequenceExperiment.Mode.WITHOUT_PLAN, null, q -> "invented-move"));
    }

    public void normalForgeBaselineCompletesEveryPreparedTurn() throws IOException {
        for (String id : TurnSequenceExperiment.scenarioIds()) {
            JsonObject result = TurnSequenceExperiment.simulate(id, TurnSequenceExperiment.Mode.FORGE, null,
                    q -> { throw new AssertionError("Baseline must not ask the model."); });
            assertTrue(result.get("fullTurnCompleted").getAsBoolean(), id);
            assertTrue(result.get("decisions").getAsInt() > 0, id);
        }
    }

    public void plansAreCompactAdvisoryContextAndBoundToTheActualUnorderedDeck() throws IOException {
        JsonObject input = DeckStrategyPlanner.deckInput(TurnSequenceExperiment.deck());
        assertEquals(input.get("cardCount").getAsInt(), 60);
        assertFalse(input.has("libraryOrder"));
        assertFalse(input.has("hand"));
        JsonObject record = new JsonObject();
        String hash = DecisionBenchmark.digest(input.toString());
        record.addProperty("deckHash", hash);
        record.add("summary", DeckStrategyPlanner.validatePlan("{\"strategy\":\"Build the combo and reserve protection mana.\",\"cautions\":\"Do not infer hidden cards.\"}"));
        List<JsonObject> questions = new ArrayList<>();
        JsonObject result = TurnSequenceExperiment.simulate("stage-clean", TurnSequenceExperiment.Mode.WITH_PLAN, record,
                q -> { questions.add(q.deepCopy()); return sensibleChoice(q); });
        assertTrue(result.get("objectivePassed").getAsBoolean());
        assertTrue(questions.stream().allMatch(q -> q.has("deckStrategy") && !q.has("mainDeck")));
        assertThrows(IOException.class, () -> DeckStrategyPlanner.attach(new JsonObject(), record, "different-deck"));
        assertThrows(IOException.class, () -> DeckStrategyPlanner.validatePlan("{\"strategy\":\"x\",\"cautions\":\"y\",\"extra\":true}"));
    }

    public void deckPlanUsesAGenerousTotalSafetyLimit() throws IOException {
        JsonObject plan = new JsonObject();
        plan.addProperty("strategy", "x".repeat(DeckStrategyPlanner.MAX_PLAN_CHARACTERS - 1));
        plan.addProperty("cautions", "y");
        assertEquals(DeckStrategyPlanner.validatePlan(plan.toString()), plan);
        plan.addProperty("cautions", "yy");
        assertThrows(IOException.class, () -> DeckStrategyPlanner.validatePlan(plan.toString()));
        plan.addProperty("strategy", 42);
        assertThrows(IOException.class, () -> DeckStrategyPlanner.validatePlan(plan.toString()));
    }

    public void deckAnalysisIsCachedWithoutAnotherModelRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            JsonObject request = LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(request.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("pregame strategy"));
            JsonObject message = new JsonObject();
            message.addProperty("content", "{\"strategy\":\"Assemble Depths with Stage or Hexmage; keep blue protection.\",\"cautions\":\"Visible Karakas and actual available mana matter.\"}");
            byte[] body = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ProviderClient provider = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
            Path directory = Files.createTempDirectory(Path.of("target"), "deck-plan-");
            var deck = TurnSequenceExperiment.deck();
            JsonObject first = DeckStrategyPlanner.plan(deck, directory, provider, "model", "high", Duration.ofSeconds(5));
            assertEquals(DeckStrategyPlanner.plan(deck, directory, provider, "model", "high", Duration.ofSeconds(5)), first);
            assertEquals(calls.get(), 1);
        } finally { server.stop(0); }
    }
}
