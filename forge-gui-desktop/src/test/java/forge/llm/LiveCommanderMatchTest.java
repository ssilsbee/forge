package forge.llm;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;
import org.testng.SkipException;
import org.testng.annotations.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.testng.Assert.*;

/** Explicit live-account opt-in; a normal complete game with the advisor attached to one seat. */
public class LiveCommanderMatchTest extends AITest {
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private Deck deck(String commander, String land, byte color, Path output) throws Exception {
        Deck deck = new Deck(commander + " evaluation");
        deck.getOrCreate(DeckSection.Commander).add(FModel.getMagicDb().getCommonCards().getCard(commander));
        deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard(land), 37);
        List<PaperCard> pool = new ArrayList<>(FModel.getMagicDb().getCommonCards().getUniqueCards());
        pool.removeIf(c -> !DeckFormat.Commander.isLegalCard(c) || !FModel.getFormats().get("Commander").getFilterPrinted().test(c)
                || forge.StaticData.instance().getEditions().get(c.getEdition()) == null
                || forge.StaticData.instance().getEditions().get(c.getEdition()).getType() == forge.card.CardEdition.Type.FUNNY
                || c.getRules().isCustom() || !c.getRules().getType().isCreature() || c.getRules().getType().isLegendary()
                || c.getRules().getColorIdentity().getColor() != color || c.getRules().getIntPower() < 1
                || c.getRules().getOracleText().length() > 120 || c.getRules().getOtherPart() != null
                || c.getRules().getManaCost().countX() > 0);
        pool.sort(Comparator.comparing(PaperCard::getName));
        int[] quota = {0, 7, 14, 14, 13, 9, 5};
        for (int cost = 1; cost <= 6; cost++) {
            int wanted = quota[cost];
            for (PaperCard c : pool) {
                if (wanted == 0) { break; }
                if (c.getRules().getManaCost().getCMC() == cost) { deck.getMain().add(c); wanted--; }
            }
            assertEquals(wanted, 0, "Insufficient simple creatures at cost " + cost);
        }
        assertEquals(deck.getMain().countAll(), 99);
        assertNull(DeckFormat.Commander.getDeckConformanceProblem(deck));
        StringBuilder text = new StringBuilder("[metadata]\nName=").append(deck.getName()).append("\n[Commander]\n1 ").append(commander).append("\n[Main]\n");
        for (var entry : deck.getMain()) { text.append(entry.getValue()).append(' ').append(entry.getKey().getName()).append('\n'); }
        Files.createDirectories(output); Files.writeString(output.resolve(commander.split(",")[0] + ".dck"), text);
        return deck;
    }

    @Test public void completeLiveCommanderMatch() throws Exception {
        if (!Boolean.getBoolean("forge.llm.liveEvaluation")) { throw new SkipException("Live-account opt-in required"); }
        Path output = Path.of(System.getProperty("forge.llm.evaluation.output", "target/commander-match"));
        Path data = Path.of(System.getProperty("forge.llm.dataDir", "../.forge-llm"));
        MyRandom.setRandom(new Random(20261029L));
        Deck green = deck("Goreclaw, Terror of Qal Sisma", "Forest", MagicColor.GREEN, output);
        Deck white = deck("Isamaru, Hound of Konda", "Plains", MagicColor.WHITE, output);
        var players = List.of(RegisteredPlayer.forCommander(green).setPlayer(new LobbyPlayerAi("Luna Goreclaw", null)),
                RegisteredPlayer.forCommander(white).setPlayer(new LobbyPlayerAi("Forge Isamaru", null)));
        GameRules rules = new GameRules(GameType.Constructed); rules.addAppliedVariant(GameType.Commander);
        Match match = new Match(rules, players, "Live Commander evaluation"); var game = match.createGame();
        game.AI_FLAT_MONTE_CARLO = false;
        for (Player p : game.getPlayers()) { ((PlayerControllerAi)p.getController()).getAi().setFlatMonteCarloOverride(false); }
        Player owner = game.getPlayers().get(0);
        JsonArray exchanges = new JsonArray(); Map<String, JsonObject> byId = new ConcurrentHashMap<>();
        ProviderClient provider = ProviderClient.chatGpt(new ChatGptAuth(data.resolve("connections")), null);
        try (LiveAdvisor advisor = new LiveAdvisor(provider, "gpt-6-luna", "high", Duration.ofSeconds(60), data)) {
            advisor.setMonitor(new LiveAdvisor.Monitor() {
                @Override public synchronized void request(String id, String instructions, JsonObject input) {
                    JsonObject entry = new JsonObject(); entry.addProperty("id", id); entry.add("input", input.deepCopy());
                    byId.put(id, entry); exchanges.add(entry);
                    System.out.println("COMMANDER REQUEST " + (input.has("turn") ? input.get("turn") + "/" + input.get("phase") : "deck plan")
                            + " " + (input.has("choiceKind") ? input.get("choiceKind") : "action"));
                }
                @Override public synchronized void response(String id, JsonObject reply) {
                    byId.get(id).add("output", reply.deepCopy());
                    try { PrivateFiles.write(output.resolve("exchanges.json"), JSON.toJson(exchanges)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void status(String id, String message) { System.out.println("COMMANDER STATUS " + message); }
            });
            LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
                @Override public boolean isEnabled() { return advisor.isEnabled(); }
                @Override public void prepare(Player p) { if (p == owner) { advisor.prepare(p); } }
                @Override public boolean shouldReview(Player p, JsonObject q) throws Exception { return p == owner && advisor.shouldReview(p, q); }
                @Override public String choose(Player p, JsonObject q) throws Exception { assertSame(p, owner); return advisor.choose(p, q); }
                @Override public void completed(String id, boolean accepted) { advisor.completed(id, accepted); }
                @Override public void diagnostic(String category, String detail) { advisor.diagnostic(category, detail); }
                @Override public void status(String id, String message) { advisor.status(id, message); }
            });
            ExecutorService runner = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(null, r, "commander-match", 64L << 20); t.setDaemon(true); return t; });
            long start = System.nanoTime(); Future<?> running = runner.submit(() -> match.startGame(game));
            boolean completed = false;
            try { running.get(40, TimeUnit.MINUTES); completed = game.isGameOver(); }
            finally {
                running.cancel(true); runner.shutdownNow(); LiveDecisionBridge.install(null);
                JsonObject result = new JsonObject(); result.addProperty("completed", completed);
                result.addProperty("turn", game.getPhaseHandler().getTurn()); result.addProperty("wallMillis", (System.nanoTime()-start)/1_000_000);
                result.addProperty("outcome", String.valueOf(game.getOutcome())); result.addProperty("advisorEnabledAtEnd", advisor.isEnabled());
                if (game.getOutcome() != null && !game.getOutcome().isDraw()) {
                    result.addProperty("winner", game.getOutcome().getWinningLobbyPlayer().getName());
                }
                result.add("finalState", forge.ai.llm.LandDecisionSnapshot.visibleState(owner));
                result.addProperty("requestsIncludingPlan", exchanges.size());
                result.addProperty("insights", advisor.insights());
                PrivateFiles.write(output.resolve("result.json"), JSON.toJson(result));
                PrivateFiles.write(output.resolve("game-log.txt"), game.getGameLog().getAllEntries().toString());
                System.out.println("COMMANDER RESULT " + JSON.toJson(result));
            }
            assertTrue(completed); assertTrue(exchanges.size() > 1, "Advisor was bypassed");
        }
    }
}

