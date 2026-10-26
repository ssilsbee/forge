package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LandDecisionSnapshot;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveTutorChoiceTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }
    private void advisor(Function<JsonObject, String> policy) {
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player player, JsonObject input) { return policy.apply(input); }
            @Override public void status(String id, String message) { }
        });
    }
    private Player position(Game game) {
        for (Player player : game.getPlayers()) { player.setLife(20, null); }
        return game.getPhaseHandler().getPlayerTurn();
    }
    private SpellAbility tinker(Player player) {
        return addCardToZone("Tinker", player, ZoneType.Hand).getFirstSpellAbility().copy(player);
    }
    private String select(JsonObject input, String name) {
        return input.getAsJsonArray("candidates").asList().stream().map(c -> c.getAsJsonObject())
                .filter(c -> c.get("description").getAsString().contains("(" + name + ")"))
                .findFirst().orElseThrow().get("actionId").getAsString();
    }

    public void resolvingTinkerPaysItsCostAndUsesTheModelSearchChoice() {
        Game game = initAndCreateGame(); Player ai = position(game), opponent = game.getPlayers().get(0);
        Card sacrifice = addCard("Bonesplitter", ai);
        for (int i = 0; i < 3; i++) { addCard("Island", ai); }
        Card colossus = addCardToZone("Darksteel Colossus", ai, ZoneType.Library);
        addCardToZone("Jayemdae Tome", ai, ZoneType.Library); addCardToZone("Ornithopter", ai, ZoneType.Library);
        addCardToZone("Forest", ai, ZoneType.Library);
        addCardToZone("Wrath of God", opponent, ZoneType.Hand);
        addCardToZone("Black Lotus", opponent, ZoneType.Library);
        SpellAbility ability = tinker(ai);
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> selected = new AtomicReference<>();
        advisor(input -> {
            calls.incrementAndGet(); assertEquals(input.get("choiceKind").getAsString(), "library_search");
            assertTrue(sacrifice.isInZone(ZoneType.Graveyard), "Additional sacrifice cost must already be paid");
            assertEquals(input.getAsJsonObject("choiceContext").get("destination").getAsString(), "Battlefield");
            assertFalse(input.toString().contains("Wrath of God")); assertFalse(input.toString().contains("Black Lotus"));
            assertFalse(input.toString().contains("Forest"), "Noneligible own library cards must not be exposed");
            List<String> names = input.getAsJsonObject("choiceContext").getAsJsonArray("offeredCards").asList().stream()
                    .map(c -> c.getAsJsonObject().get("name").getAsString()).toList();
            assertEquals(names, List.of("Darksteel Colossus", "Jayemdae Tome", "Ornithopter"));
            String choice = select(input, "Jayemdae Tome");
            selected.set("Jayemdae Tome");
            if (choice.equals("forge")) { choice = select(input, "Ornithopter"); selected.set("Ornithopter"); }
            assertNotEquals(choice, "forge"); return choice;
        });
        ai.getController().playChosenSpellAbility(ability);
        assertEquals(game.getStack().size(), 1); game.getStack().resolveStack();
        assertEquals(calls.get(), 1);
        assertTrue(ai.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals(selected.get())));
        assertTrue(colossus.isInZone(ZoneType.Library)); assertFalse(colossus.getView().canBeShownTo(ai.getView()));
        assertFalse(LandDecisionSnapshot.visibleState(ai).toString().contains("Darksteel Colossus"));
    }

    public void missingOrBlindRevealPermissionKeepsTheOriginalChoice() {
        Game game = initAndCreateGame(); Player ai = position(game);
        Card first = addCardToZone("Jayemdae Tome", ai, ZoneType.Library);
        Card second = addCardToZone("Ornithopter", ai, ZoneType.Library);
        CardCollection pool = new CardCollection(List.of(first, second)); SpellAbility ability = tinker(ai);
        advisor(input -> { fail("A blind or unauthorized search must not call the model"); return "forge"; });
        Card choice = ai.getController().chooseSingleCardForZoneChange(ZoneType.Battlefield, List.of(ZoneType.Library),
                ability, pool, null, "Choose an artifact", true, ai);
        assertTrue(pool.contains(choice));
        ability.putParam("NoLooking", "True");
        DelayedReveal reveal = new DelayedReveal(pool, ZoneType.Library, ai.getView());
        choice = ai.getController().chooseSingleCardForZoneChange(ZoneType.Battlefield, List.of(ZoneType.Library),
                ability, pool, reveal, "Choose an artifact without looking", true, ai);
        assertTrue(pool.contains(choice));
    }

    public void aSearchRevealDoesNotAuthorizeAnUnrevealedOpponentCard() {
        Game game = initAndCreateGame(); Player ai = position(game), opponent = game.getPlayers().get(0);
        Card own = addCardToZone("Ornithopter", ai, ZoneType.Library);
        Card hidden = addCardToZone("Black Lotus", opponent, ZoneType.Library);
        CardCollection pool = new CardCollection(List.of(own, hidden)); SpellAbility ability = tinker(ai);
        DelayedReveal reveal = new DelayedReveal(List.of(own), ZoneType.Library, ai.getView());
        advisor(input -> { fail("One card's permission must not reveal another library's card"); return "forge"; });
        Card choice = ai.getController().chooseSingleCardForZoneChange(ZoneType.Battlefield, List.of(ZoneType.Library),
                ability, pool, reveal, "Choose a card", true, ai);
        assertTrue(pool.contains(choice));
    }

    public void mindcensorSearchExposesOnlyEligibleCardsFromThePermittedTopFour() {
        Game game = initAndCreateGame(); Player ai = position(game), opponent = game.getPlayers().get(0);
        addCard("Bonesplitter", ai); for (int i = 0; i < 3; i++) { addCard("Island", ai); }
        addCard("Aven Mindcensor", opponent);
        Card tome = addCardToZone("Jayemdae Tome", ai, ZoneType.Library);
        addCardToZone("Forest", ai, ZoneType.Library); addCardToZone("Ornithopter", ai, ZoneType.Library);
        addCardToZone("Forest", ai, ZoneType.Library); addCardToZone("Darksteel Colossus", ai, ZoneType.Library);
        game.getAction().checkStateEffects(true); assertTrue(ai.hasKeyword("LimitSearchLibrary"));
        AtomicInteger calls = new AtomicInteger();
        advisor(input -> {
            calls.incrementAndGet(); assertFalse(input.toString().contains("Darksteel Colossus"));
            assertEquals(input.getAsJsonObject("choiceContext").getAsJsonArray("offeredCards").size(), 2);
            return select(input, "Jayemdae Tome");
        });
        ai.getController().playChosenSpellAbility(tinker(ai));
        assertEquals(game.getStack().size(), 1); game.getStack().resolveStack();
        assertEquals(calls.get(), 1); assertTrue(tome.isInZone(ZoneType.Battlefield));
    }
}
