package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveActivatedAbilityTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }
    private final AtomicInteger requests = new AtomicInteger(), accepted = new AtomicInteger(), rejected = new AtomicInteger();
    private void selectSource(Card source) {
        requests.set(0); accepted.set(0); rejected.set(0);
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player player, JsonObject input) {
                requests.incrementAndGet();
                return input.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                        .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + source.getId()))
                        .findFirst().orElseThrow().get("actionId").getAsString();
            }
            @Override public void status(String id, String message) { }
            @Override public void completed(String id, boolean applied) { (applied ? accepted : rejected).incrementAndGet(); }
        });
    }

    public void drawActivationPaysManaAndTapCostsBeforeAcceptance() {
        Game game = initAndCreateGame(); Player player = game.getPhaseHandler().getPlayerTurn();
        Card tome = addCard("Jayemdae Tome", player);
        for (int n = 0; n < 4; n++) { addCard("Island", player); }
        selectSource(tome);
        List<SpellAbility> chosen = LiveDecisionBridge.choose(player, null);
        assertNotNull(chosen); assertEquals(chosen.get(0).getApi(), ApiType.Draw);
        assertEquals(accepted.get(), 0, "Validation must not commit notes before costs and engine acceptance.");
        player.getController().playChosenSpellAbility(chosen.get(0));
        assertTrue(tome.isTapped()); assertEquals(game.getStack().size(), 1);
        assertEquals(player.getCardsIn(ZoneType.Battlefield).stream().filter(c -> c.getName().equals("Island") && c.isTapped()).count(), 4L);
        assertEquals(accepted.get(), 1);
    }

    public void unaffordableActivationDoesNotRequestAModel() {
        Game game = initAndCreateGame(); Player player = game.getPhaseHandler().getPlayerTurn();
        Card tome = addCard("Jayemdae Tome", player);
        for (int n = 0; n < 3; n++) { addCard("Island", player); }
        selectSource(tome); assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(requests.get(), 0);
    }

    public void tapActivationRespectsSummoningSickness() {
        Game game = initAndCreateGame(); Player player = game.getPhaseHandler().getPlayerTurn();
        Card pyromancer = addCard("Prodigal Pyromancer", player); pyromancer.setSickness(true);
        selectSource(pyromancer); assertNull(LiveDecisionBridge.choose(player, null)); assertEquals(requests.get(), 0);
        pyromancer.setSickness(false);
        List<SpellAbility> chosen = LiveDecisionBridge.choose(player, null);
        assertNotNull(chosen); assertEquals(chosen.get(0).getApi(), ApiType.DealDamage);
        player.getController().playChosenSpellAbility(chosen.get(0)); assertTrue(pyromancer.isTapped());
        assertEquals(accepted.get(), 1);
    }

    public void equipmentTargetsRemainEngineLegal() {
        Game game = initAndCreateGame(); Player player = game.getPhaseHandler().getPlayerTurn();
        Card equipment = addCard("Bonesplitter", player), creature = addCard("Grizzly Bears", player);
        addCard("Island", player); addCard("Grizzly Bears", player.getOpponents().get(0));
        selectSource(equipment); List<SpellAbility> chosen = LiveDecisionBridge.choose(player, null);
        assertNotNull(chosen); assertEquals(chosen.get(0).getApi(), ApiType.Attach);
        assertEquals(chosen.get(0).getTargets().getTargetCards().getFirst(), creature);
    }

    public void engineDeclineRejectsProposedNotes() {
        Game game = initAndCreateGame(); Player player = game.getPhaseHandler().getPlayerTurn();
        Card pyromancer = addCard("Prodigal Pyromancer", player); pyromancer.setSickness(false);
        selectSource(pyromancer); List<SpellAbility> chosen = LiveDecisionBridge.choose(player, null);
        assertNotNull(chosen);
        LiveDecisionBridge.executed(player, chosen.get(0), false);
        assertEquals(accepted.get(), 0); assertEquals(rejected.get(), 1);
    }
}
