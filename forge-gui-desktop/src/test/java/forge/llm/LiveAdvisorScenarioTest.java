package forge.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.LandDecisionSnapshot;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import static org.testng.Assert.*;

/** Explicit opt-in evaluation using real credentials and allowance; never runs network calls in normal tests. */
@Test(singleThreaded = true)
public class LiveAdvisorScenarioTest extends AITest {
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HIDDEN_CARD = "Giant Growth";
    private final Path root = Path.of(System.getProperty("forge.llm.evaluation.output", "target/live-advisor-evaluation"));

    private Deck deck(String name) throws IOException {
        Deck deck = new Deck(name);
        boolean main = false;
        for (String line : Files.readAllLines(Path.of("../tools/llm-demo-decks/LLM Eval - " + name + ".dck"))) {
            if (line.startsWith("[")) { main = line.equals("[main]"); continue; }
            if (!main || line.isBlank()) { continue; }
            String[] parts = line.split(" ", 2);
            var card = FModel.getMagicDb().getCommonCards().getCard(parts[1]);
            if (card == null) { throw new IOException("Unknown evaluation card: " + parts[1]); }
            deck.getMain().add(card, Integer.parseInt(parts[0]));
        }
        assertEquals(deck.getMain().countAll(), 60);
        return deck;
    }

    private Card add(String name, Player owner, ZoneType zone) {
        Card card = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(zone).add(card); card.setSickness(false); return card;
    }

    private void library(Player player, Deck deck) {
        TreeMap<String, Integer> left = new TreeMap<>();
        for (var entry : deck.getMain()) { left.merge(entry.getKey().getName(), entry.getValue(), Integer::sum); }
        for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield)) {
            for (Card card : player.getCardsIn(zone)) { left.compute(card.getName(), (key, count) -> count == null ? -1 : count - 1); }
        }
        List<String> names = new ArrayList<>();
        left.forEach((name, count) -> { assertTrue(count >= 0, "Fixture exceeds its registered deck"); for (int n = 0; n < count; n++) { names.add(name); } });
        Collections.shuffle(names, new Random(20261025L));
        for (String name : names) { add(name, player, ZoneType.Library); }
    }

    @Test public void evaluateLiveScenarios() throws Exception {
        if (!Boolean.getBoolean("forge.llm.liveEvaluation")) { throw new SkipException("Requires explicit live-account evaluation opt-in."); }
        String model = System.getProperty("forge.llm.model", "gpt-6-luna");
        String effort = System.getProperty("forge.llm.reasoningEffort", "high");
        Path data = Path.of(System.getProperty("forge.llm.dataDir", "../.forge-llm"));
        ProviderClient provider = ProviderClient.chatGpt(new ChatGptAuth(data.resolve("connections")), null);
        JsonArray results = new JsonArray();
        String cases = System.getProperty("forge.llm.evaluation.scenarios", "Blue Resources|White Equipment|Red Abilities|Blue Efficient Draw|Artifact Search");
        for (String scenario : cases.split("\\|")) {
            System.out.println("LIVE SCENARIO START: " + scenario);
            JsonObject result = evaluate(scenario, provider, model, effort, data);
            results.add(result);
            PrivateFiles.write(root.resolve("results.json"), JSON.toJson(results));
            System.out.println("LIVE SCENARIO FINISHED: " + scenario);
        }
    }

    private JsonObject evaluate(String scenario, ProviderClient provider, String model, String effort, Path data) throws Exception {
        Deck own = deck(scenario), other = deck("Green Opponent");
        List<RegisteredPlayer> players = List.of(new RegisteredPlayer(own).setPlayer(new LobbyPlayerAi("Advisor AI", null)),
                new RegisteredPlayer(other).setPlayer(new LobbyPlayerAi("Scripted opponent", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        Game game = new Game(players, rules, new Match(rules, players, scenario));
        game.setAge(GameStage.Play); game.AI_FLAT_MONTE_CARLO = false;
        Player ai = game.getPlayers().get(0), opponent = game.getPlayers().get(1);
        ai.setLife(20, null); opponent.setLife(12, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 4);
        game.getPhaseHandler().onStackResolved(); game.getPhaseHandler().setPriority(ai);
        add(HIDDEN_CARD, opponent, ZoneType.Hand);
        for (int n = 0; n < 3; n++) { add("Forest", opponent, ZoneType.Battlefield); }
        Card sick = null;
        if (scenario.startsWith("Blue ")) {
            for (int n = 0; n < 4; n++) { add("Island", ai, ZoneType.Battlefield); }
            add("Jayemdae Tome", ai, ZoneType.Battlefield);
            add(scenario.equals("Blue Efficient Draw") ? "Archivist" : "Azure Mage", ai, ZoneType.Battlefield);
            add("Counterspell", ai, ZoneType.Hand); add("Grizzly Bears", opponent, ZoneType.Battlefield);
        } else if (scenario.equals("White Equipment")) {
            for (int n = 0; n < 3; n++) { add("Plains", ai, ZoneType.Battlefield); }
            add("Bonesplitter", ai, ZoneType.Battlefield); add("Elite Vanguard", ai, ZoneType.Battlefield);
            add("Silvercoat Lion", ai, ZoneType.Battlefield); add("Wall of Wood", opponent, ZoneType.Battlefield);
        } else if (scenario.equals("Artifact Search")) {
            for (int n = 0; n < 3; n++) { add("Island", ai, ZoneType.Battlefield); }
            add("Bonesplitter", ai, ZoneType.Battlefield); add("Tinker", ai, ZoneType.Hand);
            ai.setLife(6, null); add("Grizzly Bears", opponent, ZoneType.Battlefield);
            add("Llanowar Elves", opponent, ZoneType.Battlefield);
        } else {
            for (int n = 0; n < 2; n++) { add("Mountain", ai, ZoneType.Battlefield); }
            add("Prodigal Pyromancer", ai, ZoneType.Battlefield); add("Fanatical Firebrand", ai, ZoneType.Battlefield);
            sick = add("Prodigal Pyromancer", ai, ZoneType.Battlefield); sick.setSickness(true);
            add("Llanowar Elves", opponent, ZoneType.Battlefield);
        }
        library(ai, own); library(opponent, other); game.getAction().checkStateEffects(true);
        JsonObject result = new JsonObject(); result.addProperty("scenario", scenario);
        result.addProperty("model", model); result.addProperty("reasoningEffort", effort);
        result.addProperty("scope", "Controlled engine scenario with live model calls; not a complete match or phone UI test.");
        result.add("initialState", LandDecisionSnapshot.visibleState(ai));
        JsonArray exchanges = new JsonArray(), events = new JsonArray(), steps = new JsonArray();
        java.util.Map<String, JsonObject> recorded = new java.util.concurrent.ConcurrentHashMap<>();
        Path directory = root.resolve(scenario.replace(' ', '-').toLowerCase(java.util.Locale.ROOT));
        try (LiveAdvisor advisor = new LiveAdvisor(provider, model, effort, Duration.ofSeconds(60), data)) {
            advisor.setMonitor(new LiveAdvisor.Monitor() {
                @Override public synchronized void request(String id, String instructions, JsonObject input) {
                    JsonObject engineInput = input.deepCopy();
                    engineInput.remove("deckStrategy"); engineInput.remove("strategyMemory");
                    assertFalse(engineInput.toString().contains(HIDDEN_CARD), "Hidden opponent hand leaked into engine-provided context");
                    JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.add("input", input.deepCopy());
                    recorded.put(id, entry); exchanges.add(entry);
                    System.out.println("LIVE REQUEST: " + scenario + " / " + (id.endsWith(" / deck plan") ? "deck plan" : input.has("choiceKind") ? input.get("choiceKind").getAsString() : "action"));
                }
                @Override public synchronized void response(String id, JsonObject output) {
                    recorded.get(id).add("output", output.deepCopy());
                    try { PrivateFiles.write(directory.resolve("exchanges.json"), JSON.toJson(exchanges)); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                @Override public synchronized void status(String id, String message) {
                    events.add(id + ": " + message);
                    System.out.println("LIVE STATUS: " + message);
                }
            });
            LiveDecisionBridge.install(advisor);
            try {
                ai.getController().prepareForGame();
                if (scenario.equals("Artifact Search")) {
                    // Deliberately cast the tutor to isolate the live library-search resolution choice.
                    Card source = ai.getCardsIn(ZoneType.Hand).stream().filter(c -> c.getName().equals("Tinker")).findFirst().orElseThrow();
                    ai.getController().playChosenSpellAbility(source.getFirstSpellAbility().copy(ai));
                    assertEquals(game.getStack().size(), 1);
                    game.getStack().resolveStack(); game.getAction().checkStateEffects(true);
                    JsonObject step = new JsonObject(); step.addProperty("selected", "Scripted Tinker cast; model chose the searched artifact");
                    step.add("stateAfter", LandDecisionSnapshot.visibleState(ai)); steps.add(step);
                    assertTrue(recorded.values().stream().anyMatch(entry -> entry.getAsJsonObject("input").has("choiceKind")
                            && entry.getAsJsonObject("input").get("choiceKind").getAsString().equals("library_search")));
                }
                boolean advanced = false;
                for (int n = 0; n < 4 && !game.isGameOver(); n++) {
                    game.getPhaseHandler().setPriority(ai);
                    var chosen = ai.getController().chooseSpellAbilityToPlay();
                    for (JsonObject exchange : recorded.values()) {
                        if (exchange.has("output") && exchange.getAsJsonObject("output").has("status")) {
                            assertNotEquals(exchange.getAsJsonObject("output").get("status").getAsString(), "provider_error", "Live connection failed; stopping evaluation.");
                        }
                    }
                    JsonObject step = new JsonObject(); step.addProperty("step", n + 1);
                    step.addProperty("phase", game.getPhaseHandler().getPhase().name());
                    if (chosen == null) {
                        step.addProperty("selected", "pass"); steps.add(step);
                        if (!scenario.equals("White Equipment") && !advanced) {
                            advanced = true;
                            game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opponent, 5);
                            game.getPhaseHandler().onStackResolved(); continue;
                        }
                        break;
                    }
                    JsonArray sources = new JsonArray();
                    for (var ability : chosen) {
                        sources.add(ability.getHostCard().getName() + " / " + ability.getApi());
                        ai.getController().playChosenSpellAbility(ability);
                        int limit = 0;
                        while (!game.getStack().isEmpty()) {
                            assertTrue(++limit <= 12, "Unexpected stack loop");
                            game.getStack().resolveStack(); game.getAction().checkStateEffects(true);
                        }
                    }
                    step.add("selected", sources); step.add("stateAfter", LandDecisionSnapshot.visibleState(ai)); steps.add(step);
                }
                if (scenario.equals("White Equipment")) {
                    game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, ai, 4);
                    Combat combat = new Combat(ai); game.getPhaseHandler().setCombat(combat);
                    ai.getController().declareAttackers(ai, combat);
                    assertTrue(CombatUtil.validateAttackers(combat));
                    JsonArray attacks = new JsonArray();
                    for (var attack : combat.getAttackersAndDefenders().entrySet()) {
                        JsonObject entry = new JsonObject();
                        entry.addProperty("attacker", attack.getKey().getName());
                        entry.addProperty("defender", attack.getValue().getName()); attacks.add(entry);
                    }
                    result.add("attackDeclaration", attacks);
                }
                if (sick != null) { assertFalse(sick.isTapped(), "A sick pinger must not have activated its tap ability"); }
                assertFalse(exchanges.isEmpty(), "No live advisor request was observed");
                result.add("steps", steps); result.add("exchanges", exchanges); result.add("events", events);
                result.add("finalState", LandDecisionSnapshot.visibleState(ai));
                result.addProperty("insights", advisor.insights());
                PrivateFiles.write(directory.resolve("result.json"), JSON.toJson(result));
            } finally { LiveDecisionBridge.install(null); }
        }
        return result;
    }
}
