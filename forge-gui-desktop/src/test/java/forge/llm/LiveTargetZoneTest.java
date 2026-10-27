package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveTargetZoneTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }

    public void removalCannotTargetPendingArtifactButCounterspellCan() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        for (Player p : game.getPlayers()) { p.setLife(20, null); }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, 2);
        addCard("Island", opponent); addCard("Island", opponent);
        addCard("Island", ai); addCard("Island", ai); addCard("Plains", ai);
        Card mine = addCardToZone("Howling Mine", opponent, ZoneType.Hand);
        Card removal = addCardToZone("Disenchant", ai, ZoneType.Hand);
        Card counter = addCardToZone("Counterspell", ai, ZoneType.Hand);
        opponent.getController().playChosenSpellAbility(mine.getFirstSpellAbility().copy(opponent));
        assertEquals(game.getStack().size(), 1); assertTrue(mine.isInZone(ZoneType.Stack));
        game.getPhaseHandler().setPriority(ai);
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) { captured.set(q.deepCopy()); return "pass"; }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null));
        JsonObject pending = captured.get(); assertNotNull(pending);
        assertEquals(pending.getAsJsonArray("stack").get(0).getAsJsonObject().get("source").getAsString(), "Howling Mine");
        assertTrue(pending.getAsJsonArray("players").asList().stream()
                .flatMap(p -> p.getAsJsonObject().getAsJsonArray("Battlefield").asList().stream())
                .noneMatch(c -> c.getAsJsonObject().get("id").getAsString().equals("card-" + mine.getId())));
        assertTrue(hasSource(pending, counter), "A legal counterspell must still be offered against the pending artifact.");
        assertFalse(hasSource(pending, removal), "Disenchant must not be offered against an artifact on the stack.");

        game.getStack().resolveStack();
        Card resolvedMine = game.getCardState(mine);
        assertTrue(resolvedMine.isInZone(ZoneType.Battlefield));
        captured.set(null); game.getPhaseHandler().setPriority(ai);
        assertNull(LiveDecisionBridge.choose(ai, null));
        JsonObject resolved = captured.get(); assertNotNull(resolved);
        assertTrue(hasSource(resolved, removal), "Disenchant becomes a legal response after the artifact resolves.");
        assertFalse(hasSource(resolved, counter));
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                return q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                        .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + removal.getId()))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing legal post-resolution Disenchant"))
                        .get("actionId").getAsString();
            }
            @Override public void status(String id, String message) { }
        });
        var chosen = LiveDecisionBridge.choose(ai, null); assertNotNull(chosen);
        assertSame(chosen.get(0).getTargets().getTargetCards().getFirst(), resolvedMine);
        ai.getController().playChosenSpellAbility(chosen.get(0));
        assertEquals(game.getStack().size(), 1); game.getStack().resolveStack();
        assertTrue(game.getCardState(resolvedMine).isInZone(ZoneType.Graveyard), "Legal post-resolution Disenchant must actually destroy the artifact.");
    }

    public void legitimateCounterspellActuallyCountersAnArtifactOnTheStack() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        for (Player p : game.getPlayers()) { p.setLife(20, null); }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, 2);
        addCard("Island", opponent); addCard("Island", opponent); addCard("Island", ai); addCard("Island", ai);
        Card mine = addCardToZone("Howling Mine", opponent, ZoneType.Hand);
        Card counter = addCardToZone("Counterspell", ai, ZoneType.Hand);
        opponent.getController().playChosenSpellAbility(mine.getFirstSpellAbility().copy(opponent));
        assertEquals(game.getStack().size(), 1); game.getPhaseHandler().setPriority(ai);
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                return q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                        .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + counter.getId()))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing legal Counterspell"))
                        .get("actionId").getAsString();
            }
            @Override public void status(String id, String message) { }
        });
        var chosen = LiveDecisionBridge.choose(ai, null); assertNotNull(chosen);
        ai.getController().playChosenSpellAbility(chosen.get(0)); assertEquals(game.getStack().size(), 2);
        game.getStack().resolveStack(); assertTrue(game.getStack().isEmpty());
        assertTrue(game.getCardState(mine).isInZone(ZoneType.Graveyard));
        assertTrue(opponent.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c -> c.getName().equals("Howling Mine")));
    }

    private boolean hasSource(JsonObject input, Card source) {
        return input.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                .anyMatch(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + source.getId()));
    }

    public void stifleCanCounterAnActivatedAbilityWhoseSourceIsOnTheBattlefield() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        for (Player p : game.getPlayers()) { p.setLife(20, null); }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, 2);
        Card pyromancer = addCard("Prodigal Pyromancer", opponent); pyromancer.setSickness(false);
        addCard("Island", ai); addCard("Island", ai);
        Card stifle = addCardToZone("Stifle", ai, ZoneType.Hand);
        Card counterspell = addCardToZone("Counterspell", ai, ZoneType.Hand);
        var ping = pyromancer.getSpellAbilities().stream()
                .filter(sa -> sa.getApi() == forge.game.ability.ApiType.DealDamage).findFirst().orElseThrow().copy(opponent);
        ping.getTargets().add(ai); opponent.getController().playChosenSpellAbility(ping);
        assertEquals(game.getStack().size(), 1); game.getPhaseHandler().setPriority(ai);
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                assertFalse(hasSource(q, counterspell), "Counterspell cannot counter an activated ability.");
                return q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                        .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + stifle.getId()))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing legal Stifle"))
                        .get("actionId").getAsString();
            }
            @Override public void status(String id, String message) { }
        });
        var chosen = LiveDecisionBridge.choose(ai, null); assertNotNull(chosen);
        ai.getController().playChosenSpellAbility(chosen.get(0)); assertEquals(game.getStack().size(), 2);
        game.getStack().resolveStack(); assertTrue(game.getStack().isEmpty());
        assertEquals(ai.getLife(), 20); assertTrue(pyromancer.isTapped());
        assertTrue(pyromancer.isInZone(ZoneType.Battlefield), "The ability's battlefield source is not countered.");
    }
}
