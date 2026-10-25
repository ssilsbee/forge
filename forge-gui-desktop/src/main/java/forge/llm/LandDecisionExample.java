package forge.llm;

import com.google.gson.JsonObject;
import forge.GuiDesktop;
import forge.ai.LobbyPlayerAi;
import forge.ai.llm.LandDecisionSnapshot;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.io.IOException;
import java.util.List;

/** Rebuilds a real engine position, then records the actual AI choice; no model call. */
public final class LandDecisionExample {
    private static boolean initialized;
    private LandDecisionExample() { }

    public static synchronized void initialize() {
        if (initialized) { return; }
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        initialized = true;
    }

    public static JsonObject create() throws IOException {
        initialize();
        GameRules rules = new GameRules(GameType.Constructed);
        List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Advisor AI", null)),
                new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("Opponent", null)));
        Match match = new Match(rules, players, "LLM land sequencing example");
        Game game = new Game(players, rules, match);
        Player ai = game.getPlayers().get(0);
        Player opponent = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().onStackResolved();
        add("Thespian's Stage", ai, ZoneType.Battlefield);
        add("Island", ai, ZoneType.Battlefield);
        add("Island", ai, ZoneType.Battlefield);
        add("Dark Depths", ai, ZoneType.Hand);
        add("Swamp", ai, ZoneType.Hand);
        add("Plains", opponent, ZoneType.Battlefield);
        add("Wrath of God", opponent, ZoneType.Hand);
        for (int i = 0; i < 10; i++) {
            add("Forest", ai, ZoneType.Library);
            add("Mountain", opponent, ZoneType.Library);
        }
        game.getAction().checkStateEffects(true);
        List<SpellAbility> baseline = ai.getController().chooseSpellAbilityToPlay();
        if (baseline == null || baseline.size() != 1 || !baseline.get(0).isLandAbility()) {
            throw new IOException("The example did not produce a land-drop choice.");
        }
        JsonObject snapshot = LandDecisionSnapshot.capture(ai, baseline.get(0).getHostCard());
        if (snapshot == null) { throw new IOException("The example fell outside the supported decision slice."); }
        return snapshot;
    }

    private static void add(String name, Player player, ZoneType zone) throws IOException {
        var paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) { throw new IOException("Missing example card: " + name); }
        Card card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card);
    }
}
