package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveMoveCoverageTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }
    private Player position(Game game) {
        for (Player p : game.getPlayers()) { p.setLife(20, null); }
        return game.getPhaseHandler().getPlayerTurn();
    }
    private String select(JsonObject q, Card card, Player target) {
        String targetId = "p" + card.getGame().getPlayers().indexOf(target);
        return q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + card.getId()))
                .filter(c -> target == null || c.getAsJsonArray("targetIds").asList().stream().anyMatch(t -> t.getAsString().equals(targetId)))
                .findFirst().orElseThrow(() -> new AssertionError("Missing candidate for " + card.getName()))
                .get("actionId").getAsString();
    }
    private void resolve(Game game) {
        int count = 0;
        while (!game.getStack().isEmpty()) { assertTrue(++count <= 6); game.getStack().resolveStack(); }
    }

    public void ritualEnablesHymnEvenWhenForgeProposesScepter() {
        Game game = initAndCreateGame(); Player ai = position(game), opponent = ai.getOpponents().get(0);
        Card swamp = addCard("Swamp", ai);
        Card ritual = addCardToZone("Dark Ritual", ai, ZoneType.Hand);
        Card hymn = addCardToZone("Hymn to Tourach", ai, ZoneType.Hand);
        Card scepter = addCardToZone("Disrupting Scepter", ai, ZoneType.Hand);
        addCardToZone("Grizzly Bears", opponent, ZoneType.Hand); addCardToZone("Forest", opponent, ZoneType.Hand);
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                if (calls.incrementAndGet() == 1) {
                    assertTrue(q.getAsJsonArray("candidates").asList().stream().noneMatch(c -> c.getAsJsonObject().has("sourceCardId")
                            && c.getAsJsonObject().get("sourceCardId").getAsString().equals("card-" + hymn.getId())));
                    return select(q, ritual, null);
                }
                assertEquals(q.get("baselineActionId").getAsString(), "forge");
                return select(q, hymn, opponent);
            }
            @Override public void status(String id, String message) { }
        });
        var first = LiveDecisionBridge.choose(ai, null); assertNotNull(first);
        ai.getController().playChosenSpellAbility(first.get(0)); resolve(game);
        assertTrue(swamp.isTapped()); assertEquals(ai.getManaPool().getAmountOfColor(forge.card.MagicColor.BLACK), 3);
        var second = LiveDecisionBridge.choose(ai, List.of(scepter.getFirstSpellAbility().copy(ai))); assertNotNull(second);
        assertEquals(second.get(0).getHostCard(), hymn);
        ai.getController().playChosenSpellAbility(second.get(0)); resolve(game);
        assertEquals(opponent.getCardsIn(ZoneType.Hand).size(), 0);
        assertEquals(ai.getManaPool().getAmountOfColor(forge.card.MagicColor.BLACK), 1);
        assertTrue(scepter.isInZone(ZoneType.Hand)); assertEquals(calls.get(), 2);
    }

    public void discardActivationIsOfferedAndPaysRealCosts() {
        Game game = initAndCreateGame(); Player ai = position(game), opponent = ai.getOpponents().get(0);
        Card scepter = addCard("Disrupting Scepter", ai);
        for (int n = 0; n < 3; n++) { addCard("Swamp", ai); }
        addCardToZone("Forest", opponent, ZoneType.Hand); addCardToZone("Grizzly Bears", opponent, ZoneType.Hand);
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) { return select(q, scepter, opponent); }
            @Override public void status(String id, String message) { }
        });
        var chosen = LiveDecisionBridge.choose(ai, null); assertNotNull(chosen);
        ai.getController().playChosenSpellAbility(chosen.get(0)); resolve(game);
        assertTrue(scepter.isTapped()); assertEquals(opponent.getCardsIn(ZoneType.Hand).size(), 1);
        assertEquals(ai.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Swamp") && c.isTapped()).count(), 3L);
    }

    public void illegalIdGetsOneCorrectionWithFreshIdAndCandidates() {
        Game game = initAndCreateGame(); Player ai = position(game);
        Card land = addCardToZone("Swamp", ai, ZoneType.Hand);
        AtomicInteger calls = new AtomicInteger(); List<String> ids = new ArrayList<>(); List<Boolean> applied = new ArrayList<>();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                ids.add(q.get("decisionId").getAsString());
                if (calls.incrementAndGet() == 1) { assertFalse(q.has("correction")); return "invented"; }
                assertEquals(q.getAsJsonObject("correction").get("maxRetries").getAsInt(), 1);
                return select(q, land, null);
            }
            @Override public void completed(String id, boolean accepted) { applied.add(accepted); }
            @Override public void status(String id, String message) { }
        });
        var chosen = LiveDecisionBridge.choose(ai, null); assertNotNull(chosen);
        assertEquals(calls.get(), 2); assertNotEquals(ids.get(0), ids.get(1)); assertEquals(applied, List.of(false));
        ai.getController().playChosenSpellAbility(chosen.get(0)); assertEquals(applied, List.of(false, true));
    }

    public void ordinaryDrawDiscardAndPumpSpellsAreIndependentAlternatives() {
        Game game = initAndCreateGame(); Player ai = position(game);
        for (String name : List.of("Island", "Island", "Island", "Swamp", "Forest")) { addCard(name, ai); }
        addCard("Grizzly Bears", ai);
        List<Card> spells = new ArrayList<>();
        for (String name : List.of("Divination", "Duress", "Giant Growth")) { spells.add(addCardToZone(name, ai, ZoneType.Hand)); }
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                calls.incrementAndGet();
                for (Card spell : spells) {
                    String id = select(q, spell, null);
                    JsonObject candidate = q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                            .filter(c -> c.get("actionId").getAsString().equals(id)).findFirst().orElseThrow();
                    assertFalse(candidate.get("manaCostNow").getAsString().isBlank(), spell.getName());
                }
                return "pass";
            }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null)); assertEquals(calls.get(), 1);
    }

    public void modalLinkedAndVariableCostAlternativesStayExcluded() {
        Game game = initAndCreateGame(); Player ai = position(game);
        for (int n = 0; n < 8; n++) { addCard("Island", ai); addCard("Mountain", ai); }
        addCardToZone("Swamp", ai, ZoneType.Hand);
        List<Card> complex = new ArrayList<>();
        for (String name : List.of("Cryptic Command", "Brainstorm", "Fireball")) { complex.add(addCardToZone(name, ai, ZoneType.Hand)); }
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                calls.incrementAndGet();
                for (Card spell : complex) {
                    assertTrue(q.getAsJsonArray("candidates").asList().stream().noneMatch(c -> c.getAsJsonObject().has("sourceCardId")
                            && c.getAsJsonObject().get("sourceCardId").getAsString().equals("card-" + spell.getId())), spell.getName());
                }
                return "pass";
            }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null)); assertEquals(calls.get(), 1);
    }

    public void twiceIllegalFallsBackButTransportFailureDoesNotRetry() {
        Game game = initAndCreateGame(); Player ai = position(game);
        Card land = addCardToZone("Swamp", ai, ZoneType.Hand);
        List<SpellAbility> baseline = List.of(land.getFirstSpellAbility().copy(ai));
        for (boolean transport : new boolean[]{false, true}) {
            AtomicInteger calls = new AtomicInteger();
            LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
                @Override public String choose(Player p, JsonObject q) throws Exception {
                    calls.incrementAndGet(); if (transport) { throw new java.io.IOException("offline"); } return "invented";
                }
                @Override public void status(String id, String message) { }
            });
            assertSame(LiveDecisionBridge.choose(ai, baseline), baseline);
            assertEquals(calls.get(), transport ? 1 : 2);
        }
    }

    public void staleMoveIsRemovedBeforeTheSecondAttempt() {
        Game game = initAndCreateGame(); Player ai = position(game);
        Card land = addCardToZone("Swamp", ai, ZoneType.Hand); addCard("Jayemdae Tome", ai);
        for (int n = 0; n < 4; n++) { addCard("Island", ai); }
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                if (calls.incrementAndGet() == 1) { String id = select(q, land, null); ai.setLandsPlayedThisTurn(1); return id; }
                assertTrue(q.has("correction"));
                assertTrue(q.getAsJsonArray("candidates").asList().stream().noneMatch(c -> c.getAsJsonObject().has("sourceCardId")
                        && c.getAsJsonObject().get("sourceCardId").getAsString().equals("card-" + land.getId())));
                return "pass";
            }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null)); assertEquals(calls.get(), 2); assertTrue(land.isInZone(ZoneType.Hand));
    }
}
