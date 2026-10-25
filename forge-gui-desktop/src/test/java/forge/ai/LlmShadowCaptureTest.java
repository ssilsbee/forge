package forge.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.llm.LandDecisionSnapshot;
import forge.ai.llm.ShadowDecisionRecorder;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LlmShadowCaptureTest extends AITest {
    private Card[] position(Game game) {
        Player ai = game.getPhaseHandler().getPlayerTurn();
        addCard("Thespian's Stage", ai);
        addCard("Island", ai);
        addCard("Island", ai);
        return new Card[] {addCardToZone("Dark Depths", ai, ZoneType.Hand),
                addCardToZone("Swamp", ai, ZoneType.Hand)};
    }

    public void capturesComboAndLegalAlternativesWithoutHiddenInformation() {
        Game game = initAndCreateGame();
        Player ai = game.getPhaseHandler().getPlayerTurn();
        Player opponent = game.getPlayers().stream().filter(p -> p != ai).findFirst().orElseThrow();
        Card[] lands = position(game);
        addCardToZone("Wrath of God", opponent, ZoneType.Hand);
        addCardToZone("Black Lotus", opponent, ZoneType.Library);
        addCardToZone("Forest", ai, ZoneType.Library);
        addCard("Plains", opponent);
        Card hidden = addCard("Serra Angel", opponent);
        assertTrue(hidden.turnFaceDown(true));
        JsonObject snapshot = LandDecisionSnapshot.capture(ai, lands[0]);
        assertNotNull(snapshot);
        assertEquals(snapshot.getAsJsonArray("candidates").size(), 2);
        String text = snapshot.toString();
        assertTrue(text.contains("Thespian's Stage"));
        assertTrue(text.contains("Dark Depths"));
        assertTrue(text.contains("Plains"));
        assertTrue(text.contains("Face-down card"));
        assertFalse(text.contains("Wrath of God"));
        assertFalse(text.contains("Black Lotus"));
        assertFalse(text.contains("Forest"));
        assertFalse(text.contains("Serra Angel"));
        assertFalse(text.contains("Vigilance"));
        assertTrue(lands[0].isInZone(ZoneType.Hand));
        assertTrue(lands[1].isInZone(ZoneType.Hand));
    }

    public void skipsSimulationCopies() {
        Game game = initAndCreateGame();
        position(game);
        Game copy = new GameCopier(game).makeCopy();
        Player ai = copy.getPhaseHandler().getPlayerTurn();
        assertTrue(copy.isNoGUIUser(), "GameCopier marks copied games with no GUI user");
        assertNull(LandDecisionSnapshot.capture(ai, ai.getCardsIn(ZoneType.Hand).get(0)));
    }

    public void skipsIdenticalOrIllegalAlternatives() {
        Game game = initAndCreateGame();
        Player ai = game.getPhaseHandler().getPlayerTurn();
        Card swamp = addCardToZone("Swamp", ai, ZoneType.Hand);
        addCardToZone("Swamp", ai, ZoneType.Hand);
        assertNull(LandDecisionSnapshot.capture(ai, swamp));
        Card unavailable = addCardToZone("Island", ai, ZoneType.Graveyard);
        assertNull(LandDecisionSnapshot.capture(ai, unavailable));
    }

    public void optInRecorderObservesActualChoiceAndDoesNotPlayIt() throws Exception {
        Game game = initAndCreateGame();
        Player ai = game.getPhaseHandler().getPlayerTurn();
        Card[] lands = position(game);
        Path directory = Files.createTempDirectory(Path.of("target"), "llm-capture-");
        String previous = System.getProperty(ShadowDecisionRecorder.DIRECTORY_PROPERTY);
        try {
            System.setProperty(ShadowDecisionRecorder.DIRECTORY_PROPERTY, directory.toString());
            List<SpellAbility> chosen = ai.getController().chooseSpellAbilityToPlay();
            assertNotNull(chosen);
            assertEquals(chosen.size(), 1);
            assertTrue(chosen.get(0).isLandAbility());
            Path capture = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (capture == null && System.nanoTime() < deadline) {
                try (var files = Files.list(directory)) {
                    capture = files.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElse(null);
                }
                if (capture == null) { Thread.sleep(20); }
            }
            assertNotNull(capture, "detached snapshot should be written by the bounded worker");
            JsonObject snapshot = JsonParser.parseString(Files.readString(capture)).getAsJsonObject();
            assertEquals(snapshot.get("baselineActionId").getAsString(), "land-" + chosen.get(0).getHostCard().getId());
            assertTrue(lands[0].isInZone(ZoneType.Hand));
            assertTrue(lands[1].isInZone(ZoneType.Hand));
        } finally {
            if (previous == null) { System.clearProperty(ShadowDecisionRecorder.DIRECTORY_PROPERTY); }
            else { System.setProperty(ShadowDecisionRecorder.DIRECTORY_PROPERTY, previous); }
        }
    }
}
