package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.LandDecisionSnapshot;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveCommanderTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }
    private Game position() {
        Game game = initAndCreateGame(); game.getRules().addAppliedVariant(GameType.Commander);
        for (Player p : game.getPlayers()) { p.setLife(40, null); }
        return game;
    }
    private Card commander(Player p, String name) {
        Card card = addCardToZone(name, p, ZoneType.Command); p.addCommander(card);
        p.getGame().getAction().checkStateEffects(true); return card;
    }
    private void select(Card card) {
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                assertEquals(q.get("format").getAsString(), "Commander");
                return q.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                        .filter(c -> c.has("sourceCardId") && c.get("sourceCardId").getAsString().equals("card-" + card.getId()))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing commander cast"))
                        .get("actionId").getAsString();
            }
            @Override public void status(String id, String message) { }
        });
    }

    public void commanderCastsAndRecastsPayRealTax() {
        Game game = position(); Player ai = game.getPlayers().get(1);
        Card c = commander(ai, "Isamaru, Hound of Konda"); addCard("Plains", ai); select(c);
        var cast = LiveDecisionBridge.choose(ai, null); assertNotNull(cast);
        ai.getController().playChosenSpellAbility(cast.get(0)); assertEquals(game.getStack().size(), 1);
        game.getStack().resolveStack(); assertEquals(ai.getCommanderCast(c), 1);
        game.getAction().moveToCommand(game.getCardState(c), null);
        game.getAction().checkStateEffects(true);
        assertNull(LiveDecisionBridge.choose(ai, null), "A tapped land cannot fund the recast.");
        for (int n = 0; n < 3; n++) { addCard("Plains", ai); }
        var recast = LiveDecisionBridge.choose(ai, null); assertNotNull(recast);
        ai.getController().playChosenSpellAbility(recast.get(0)); assertEquals(game.getStack().size(), 1);
        assertEquals(ai.getCardsIn(ZoneType.Battlefield).stream().filter(card -> card.getName().equals("Plains") && card.isTapped()).count(), 4L);
        game.getStack().resolveStack(); assertEquals(ai.getCommanderCast(c), 2);
    }

    public void taxMakesAnOtherwiseAffordableCommanderUnavailable() {
        Game game = position(); Player ai = game.getPlayers().get(1);
        Card c = commander(ai, "Isamaru, Hound of Konda"); ai.incCommanderCast(c);
        addCard("Plains", ai); addCard("Plains", ai);
        select(c); assertNull(LiveDecisionBridge.choose(ai, null));
    }

    public void commanderCastFromHandDoesNotPayCommandZoneTax() {
        Game game = position(); Player ai = game.getPlayers().get(1);
        Card c = commander(ai, "Isamaru, Hound of Konda"); ai.incCommanderCast(c); ai.incCommanderCast(c);
        ai.setCommanderReplacementSuppressed(true);
        game.getAction().moveToHand(c, null);
        ai.setCommanderReplacementSuppressed(false);
        addCard("Plains", ai); select(c);
        var cast = LiveDecisionBridge.choose(ai, null); assertNotNull(cast);
        ai.getController().playChosenSpellAbility(cast.get(0)); assertEquals(game.getStack().size(), 1);
        game.getStack().resolveStack(); assertEquals(ai.getCommanderCast(c), 2);
        assertTrue(game.getCardState(c).isInZone(ZoneType.Battlefield));
    }

    public void commanderBounceCanBeReplacedWithCommandZoneMove() {
        Game game = position(); Player ai = game.getPlayers().get(1);
        Card c = commander(ai, "Isamaru, Hound of Konda");
        Card inPlay = game.getAction().moveToPlay(c, ai, null, forge.game.ability.AbilityKey.newMap());
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                calls.incrementAndGet(); assertEquals(q.get("choiceKind").getAsString(), "commander_return"); return "command";
            }
            @Override public void status(String id, String message) { }
        });
        game.getAction().moveToHand(inPlay, null);
        assertEquals(calls.get(), 1); assertTrue(game.getCardState(c).isInZone(ZoneType.Command));
    }

    public void contextTracksIdentityDamageAndPublicCommandZonesWithoutHiddenHands() {
        Game game = position(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card own = commander(ai, "Isamaru, Hound of Konda"), other = commander(opponent, "Rograkh, Son of Rohgahh");
        ai.incCommanderCast(own); ai.addCommanderDamage(other, 19);
        addCardToZone("Wrath of God", opponent, ZoneType.Hand);
        JsonObject q = LandDecisionSnapshot.visibleState(ai);
        assertFalse(q.toString().contains("Wrath of God"));
        JsonObject p = q.getAsJsonArray("players").get(1).getAsJsonObject();
        assertEquals(p.getAsJsonArray("commanders").get(0).getAsJsonObject().get("commanderTaxGeneric").getAsInt(), 2);
        assertEquals(p.getAsJsonArray("commanderDamageReceived").get(0).getAsJsonObject().get("combatDamage").getAsInt(), 19);
        assertTrue(p.getAsJsonArray("Command").asList().stream().anyMatch(e -> e.getAsJsonObject().has("isCommander") && e.getAsJsonObject().get("isCommander").getAsBoolean()));
    }

    public void plannerSeparatesCommandersAndKeysCacheByThem() throws Exception {
        Deck deck = new Deck(); deck.getMain().add(FModel.getMagicDb().getCommonCards().getCard("Plains"), 98);
        deck.getOrCreate(DeckSection.Commander).add(FModel.getMagicDb().getCommonCards().getCard("Isamaru, Hound of Konda"));
        JsonObject first = DeckStrategyPlanner.deckInput(deck);
        deck.getOrCreate(DeckSection.Commander).add(FModel.getMagicDb().getCommonCards().getCard("Rograkh, Son of Rohgahh"));
        JsonObject pair = DeckStrategyPlanner.deckInput(deck);
        assertEquals(pair.getAsJsonArray("commanders").size(), 2); assertEquals(pair.get("cardCount").getAsInt(), 98);
        assertNotEquals(DeckStrategyPlanner.digest(first.toString()), DeckStrategyPlanner.digest(pair.toString()));
    }

    public void commanderReturnChoiceReachesTheModelAndCanDecline() {
        Game game = position(); Player ai = game.getPlayers().get(1);
        Card c = commander(ai, "Isamaru, Hound of Konda");
        AtomicInteger calls = new AtomicInteger();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) {
                calls.incrementAndGet(); assertEquals(q.get("choiceKind").getAsString(), "commander_return"); return "keep";
            }
            @Override public void status(String id, String message) { }
        });
        game.getAction().moveToGraveyard(c, null); game.getAction().checkStateEffects(true);
        assertEquals(calls.get(), 1); assertTrue(game.getCardState(c).isInZone(ZoneType.Graveyard));
    }

    public void multiplayerStillStaysWithForge() {
        Game game = initAndCreateThreePlayerGame(); game.getRules().addAppliedVariant(GameType.Commander);
        Player ai = game.getPlayers().get(1); Card c = commander(ai, "Isamaru, Hound of Konda"); addCard("Plains", ai);
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player p, JsonObject q) { throw new AssertionError("Multiplayer must stay gated"); }
            @Override public void status(String id, String message) { }
        });
        assertNull(LiveDecisionBridge.choose(ai, null));
    }
}
