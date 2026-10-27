package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveStackContextTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }

    private Game position() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        for (Player p : game.getPlayers()) { p.setLife(20, null); }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opponent, 2);
        addCard("Island", ai); addCard("Island", ai); addCardToZone("Counterspell", ai, ZoneType.Hand);
        return game;
    }

    private JsonObject capture(Game game) {
        Player ai = game.getPlayers().get(1); game.getPhaseHandler().setPriority(ai);
        AtomicReference<JsonObject> captured = new AtomicReference<>();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) { captured.set(q.deepCopy()); return "pass"; }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null)); assertNotNull(captured.get());
        return captured.get().getAsJsonArray("stack").get(0).getAsJsonObject();
    }

    @Test(dataProvider = "announcedValues")
    public void actualFireballCastExposesChosenXAndResolvesForThatAmount(int x) {
        Game game = position(); Player opponent = game.getPlayers().get(0), ai = game.getPlayers().get(1);
        for (int n = 0; n <= x; n++) { addCard("Mountain", opponent); }
        var fireball = addCardToZone("Fireball", opponent, ZoneType.Hand);
        SpellAbility cast = fireball.getFirstSpellAbility().copy(opponent);
        cast.setXManaCostPaid(x); cast.getTargets().add(ai);
        opponent.getController().playChosenSpellAbility(cast);
        assertEquals(game.getStack().size(), 1);
        assertEquals(game.getStack().peekAbility().getXManaCostPaid(), Integer.valueOf(x));
        JsonObject entry = capture(game);
        assertEquals(entry.get("source").getAsString(), "Fireball");
        assertEquals(entry.get("announcedX").getAsInt(), x);
        assertEquals(entry.getAsJsonArray("targetIds").get(0).getAsString(), "p1");
        assertTrue(entry.get("stackDescription").getAsString().contains(x + " damage"));
        game.getStack().resolveStack(); assertEquals(ai.getLife(), 20 - x);
    }

    @org.testng.annotations.DataProvider(name = "announcedValues")
    public Object[][] announcedValues() { return new Object[][]{{4}, {0}}; }

    public void copiedXValueIsPreservedAndMissingXIsNotInventedAsZero() {
        Game game = position(); Player opponent = game.getPlayers().get(0);
        var fireball = addCardToZone("Fireball", opponent, ZoneType.Hand);
        SpellAbility original = fireball.getFirstSpellAbility().copy(opponent); original.setXManaCostPaid(4);
        SpellAbility copy = original.copy(opponent); copy.setCopied(true); copy.getTargets().add(game.getPlayers().get(1));
        game.getStack().add(copy);
        assertEquals(capture(game).get("announcedX").getAsInt(), 4);
        copy.setXManaCostPaid(null);
        assertFalse(capture(game).has("announcedX"), "Unknown must not be reported as a chosen zero.");
    }

    public void ordinarySpellDoesNotGainAnXValue() {
        Game game = position(); Player opponent = game.getPlayers().get(0);
        addCard("Island", opponent); addCard("Island", opponent);
        var mine = addCardToZone("Howling Mine", opponent, ZoneType.Hand);
        opponent.getController().playChosenSpellAbility(mine.getFirstSpellAbility().copy(opponent));
        assertFalse(capture(game).has("announcedX"));
    }
}
