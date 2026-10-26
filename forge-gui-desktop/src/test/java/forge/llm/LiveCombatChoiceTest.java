package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.llm.LiveChoiceBridge;
import forge.ai.llm.LiveCombatBridge;
import forge.ai.llm.LiveDecisionBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class LiveCombatChoiceTest extends AITest {
    @AfterMethod(alwaysRun = true) public void uninstall() { LiveDecisionBridge.install(null); }

    private void advisor(Function<JsonObject, String> policy) {
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player player, JsonObject input) { return policy.apply(input); }
            @Override public void status(String id, String message) { }
        });
    }
    private String select(JsonObject input, String fragment) {
        return input.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsJsonObject())
                .filter(e -> e.get("description").getAsString().contains(fragment))
                .findFirst().orElseThrow().get("actionId").getAsString();
    }
    private Combat combat(Game game, Player attacker) {
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, attacker);
        Combat combat = new Combat(attacker); game.getPhaseHandler().setCombat(combat); return combat;
    }
    private Card ready(String name, Player player) {
        Card card = addCard(name, player); card.setSickness(false); return card;
    }

    public void modelAttackChangesTheLiveDeclarationWithoutMutatingItDuringTheRequest() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card bear = ready("Grizzly Bears", ai);
        addCardToZone("Wrath of God", opponent, ZoneType.Hand);
        Combat combat = combat(game, ai);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet(); assertTrue(combat.getAttackers().isEmpty());
            assertEquals(input.get("choiceKind").getAsString(), "declare_attackers");
            assertFalse(input.toString().contains("Wrath of God"));
            return select(input, "Grizzly Bears");
        });
        LiveCombatBridge.attackers(ai, combat);
        assertEquals(requests.get(), 1); assertTrue(combat.isAttacking(bear, opponent));
        assertTrue(CombatUtil.validateAttackers(combat));
    }

    public void sicknessContextMatchesTheEngineIncludingHasteAndOpponentCreatures() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card sick = addCard("Grizzly Bears", ai); sick.setSickness(true);
        Card ready = ready("Llanowar Elves", ai);
        Card haste = addCard("Raging Goblin", ai); haste.setSickness(true);
        Card opposing = addCard("Grizzly Bears", opponent); opposing.setSickness(true);
        Combat combat = combat(game, ai);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet();
            for (Card card : List.of(sick, ready, haste, opposing)) {
                JsonObject context = input.getAsJsonArray("players").asList().stream()
                        .flatMap(p -> p.getAsJsonObject().getAsJsonArray("Battlefield").asList().stream())
                        .map(c -> c.getAsJsonObject())
                        .filter(c -> c.get("id").getAsString().equals("card-" + card.getId()))
                        .findFirst().orElseThrow();
                assertEquals(context.get("summoningSick").getAsBoolean(), card == sick || card == opposing);
            }
            assertTrue(input.get("informationPolicy").getAsString().contains("does not prevent blocking"));
            assertTrue(input.getAsJsonArray("candidates").asList().stream()
                    .noneMatch(c -> c.getAsJsonObject().get("description").getAsString().contains("Grizzly Bears")));
            return select(input, "Raging Goblin");
        });
        LiveCombatBridge.attackers(ai, combat);
        assertEquals(requests.get(), 1);
        assertTrue(combat.isAttacking(haste, opponent));
        assertFalse(combat.isAttacking(sick));
    }

    public void combatContextLinksEquipmentToItsCreatureWithoutRevealingOpponentHands() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card creature = ready("Grizzly Bears", ai), equipment = addCard("Bonesplitter", ai);
        equipment.attachToEntity(creature, null, true);
        addCardToZone("Wrath of God", opponent, ZoneType.Hand);
        Combat combat = combat(game, ai);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet();
            var cards = input.getAsJsonArray("players").asList().stream()
                    .flatMap(p -> p.getAsJsonObject().getAsJsonArray("Battlefield").asList().stream())
                    .map(c -> c.getAsJsonObject()).toList();
            JsonObject source = cards.stream().filter(c -> c.get("id").getAsString().equals("card-" + equipment.getId())).findFirst().orElseThrow();
            JsonObject target = cards.stream().filter(c -> c.get("id").getAsString().equals("card-" + creature.getId())).findFirst().orElseThrow();
            assertEquals(source.get("attachedToId").getAsString(), "card-" + creature.getId());
            assertEquals(target.getAsJsonArray("attachmentIds").get(0).getAsString(), "card-" + equipment.getId());
            assertFalse(source.get("token").getAsBoolean());
            assertFalse(input.toString().contains("Wrath of God"));
            return "forge";
        });
        LiveCombatBridge.attackers(ai, combat);
        assertEquals(requests.get(), 1);
        assertTrue(combat.getAttackers().isEmpty());
    }

    public void mandatoryAttacksAreNeverOfferedAsOptional() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        addCard("Trove of Temptation", opponent);
        Card bear = ready("Grizzly Bears", ai), elf = ready("Llanowar Elves", ai);
        game.getAction().checkStateEffects(true);
        Combat combat = combat(game, ai); combat.addAttacker(bear, opponent); combat.addAttacker(elf, opponent);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet();
            assertFalse(input.toString().contains("Do not attack."));
            for (var entry : input.getAsJsonObject("choiceContext").getAsJsonObject("declarations").entrySet()) {
                assertTrue(entry.getValue().getAsJsonArray().size() >= 1);
            }
            return select(input, "Llanowar Elves");
        });
        LiveCombatBridge.attackers(ai, combat);
        assertEquals(requests.get(), 1); assertTrue(CombatUtil.validateAttackers(combat));
    }

    public void menaceOffersZeroOrAtLeastTwoBlockersAndPreservesBaselineWhileThinking() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card attacker = ready("Grizzly Bears", opponent); attacker.addIntrinsicKeyword("Menace");
        Card first = ready("Grizzly Bears", ai), second = ready("Llanowar Elves", ai);
        Combat combat = combat(game, opponent); combat.addAttacker(attacker, ai);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet(); assertTrue(combat.getAllBlockers().isEmpty());
            JsonObject plans = input.getAsJsonObject("choiceContext").getAsJsonObject("declarations");
            String selected = null;
            for (var entry : plans.entrySet()) {
                assertNotEquals(entry.getValue().getAsJsonArray().size(), 1);
                if (entry.getValue().getAsJsonArray().size() == 2) { selected = entry.getKey(); }
            }
            assertNotNull(selected); return selected;
        });
        LiveCombatBridge.blockers(ai, ai, combat);
        assertEquals(requests.get(), 1); assertTrue(combat.isBlocking(first, attacker));
        assertTrue(combat.isBlocking(second, attacker)); assertNull(CombatUtil.validateBlocks(combat, ai));
    }

    public void groundCreaturesCannotBeOfferedToBlockFlying() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card drake = ready("Wind Drake", opponent); ready("Grizzly Bears", ai);
        Combat combat = combat(game, opponent); combat.addAttacker(drake, ai);
        advisor(input -> { fail("No alternative legal blocks exist."); return "forge"; });
        LiveCombatBridge.blockers(ai, ai, combat); assertTrue(combat.getAllBlockers().isEmpty());
    }

    public void aCreatureWithAdditionalBlocksCanChooseTwoAttackers() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card first = ready("Grizzly Bears", opponent), second = ready("Llanowar Elves", opponent);
        Card blocker = ready("Grizzly Bears", ai); blocker.addCanBlockAdditional(1, game.getNextTimestamp());
        Combat combat = combat(game, opponent); combat.addAttacker(first, ai); combat.addAttacker(second, ai);
        advisor(input -> input.getAsJsonObject("choiceContext").getAsJsonObject("declarations").entrySet().stream()
                .filter(e -> e.getValue().getAsJsonArray().size() == 2).findFirst().orElseThrow().getKey());
        LiveCombatBridge.blockers(ai, ai, combat);
        assertTrue(combat.isBlocking(blocker, first)); assertTrue(combat.isBlocking(blocker, second));
        assertNull(CombatUtil.validateBlocks(combat, ai));
    }

    public void mandatoryBlockersCannotChooseToStayBack() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card first = ready("Grizzly Bears", opponent), second = ready("Llanowar Elves", opponent);
        Card blocker = ready("Watchdog", ai);
        Combat combat = combat(game, opponent); combat.addAttacker(first, ai); combat.addAttacker(second, ai);
        combat.addBlocker(first, blocker);
        AtomicInteger requests = new AtomicInteger();
        advisor(input -> {
            requests.incrementAndGet();
            for (var entry : input.getAsJsonObject("choiceContext").getAsJsonObject("declarations").entrySet()) {
                assertFalse(entry.getValue().getAsJsonArray().isEmpty());
            }
            return "forge";
        });
        LiveCombatBridge.blockers(ai, ai, combat);
        assertEquals(requests.get(), 1); assertTrue(combat.isBlocking(blocker, first));
    }

    public void anAttackThatBecomesIllegalWhileWaitingIsNeverApplied() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        Card bear = ready("Grizzly Bears", ai); Combat combat = combat(game, ai);
        advisor(input -> { String id = select(input, "Grizzly Bears"); bear.setTapped(true); return id; });
        LiveCombatBridge.attackers(ai, combat); assertTrue(combat.getAttackers().isEmpty());
    }

    public void theRealAiControllerRoutesCombatThroughTheAdvisor() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        ready("Grizzly Bears", ai); Combat combat = combat(game, ai); AtomicInteger requests = new AtomicInteger();
        advisor(input -> { requests.incrementAndGet(); return select(input, "Do not attack."); });
        ai.getController().declareAttackers(ai, combat);
        assertEquals(requests.get(), 1); assertTrue(combat.getAttackers().isEmpty());
    }

    public void invalidatedCombatSelectionRejectsItsPendingNotes() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        Card bear = ready("Grizzly Bears", ai); Combat combat = combat(game, ai);
        java.util.concurrent.atomic.AtomicReference<Boolean> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        LiveDecisionBridge.install(new LiveDecisionBridge.Advisor() {
            @Override public String choose(Player player, JsonObject input) {
                String id = select(input, "Grizzly Bears"); bear.setTapped(true); return id;
            }
            @Override public void status(String id, String message) { }
            @Override public void completed(String id, boolean applied) { accepted.set(applied); }
        });
        LiveCombatBridge.attackers(ai, combat);
        assertEquals(accepted.get(), Boolean.FALSE); assertTrue(combat.getAttackers().isEmpty());
    }

    public void invalidResponseAndProviderFailureKeepTheExactPreparedCombat() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card bear = ready("Grizzly Bears", ai); Combat combat = combat(game, ai); combat.addAttacker(bear, opponent);
        advisor(input -> "invented"); LiveCombatBridge.attackers(ai, combat);
        assertEquals(combat.getAttackers().size(), 1); assertTrue(combat.isAttacking(bear, opponent));
        advisor(input -> { throw new IllegalStateException("offline"); }); LiveCombatBridge.attackers(ai, combat);
        assertEquals(combat.getAttackers().size(), 1); assertTrue(combat.isAttacking(bear, opponent));
    }

    public void oversizedCombatFallsBackBeforeCallingTheProvider() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        for (int i = 0; i < 9; i++) { ready("Grizzly Bears", ai); }
        Combat combat = combat(game, ai);
        advisor(input -> { fail("Oversized enumeration must use Forge."); return "forge"; });
        LiveCombatBridge.attackers(ai, combat); assertTrue(combat.getAttackers().isEmpty());
    }

    public void sacrificeChoicesObeyCardinalityAndDoNotMoveCardsWhileThinking() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        Card bear = ready("Grizzly Bears", ai), elf = ready("Llanowar Elves", ai);
        CardCollection pool = new CardCollection(List.of(bear, elf)); CardCollection baseline = new CardCollection(bear);
        advisor(input -> { assertTrue(bear.isInPlay()); assertTrue(elf.isInPlay()); return select(input, "Llanowar Elves"); });
        CardCollectionView chosen = LiveChoiceBridge.cards(ai, pool, 1, 1, null, "Sacrifice one creature", baseline);
        assertEquals(chosen.size(), 1); assertSame(chosen.get(0), elf); assertSame(baseline.get(0), bear);
        assertTrue(elf.isInPlay());
    }

    public void hiddenEntityChoicesStayWithForge() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1), opponent = game.getPlayers().get(0);
        Card secret = addCardToZone("Black Lotus", opponent, ZoneType.Hand);
        advisor(input -> { fail("Hidden card names must not reach a provider."); return "forge"; });
        assertSame(LiveChoiceBridge.entity(ai, List.of(secret), null, "Hidden choice", false, secret), secret);
    }

    public void scryExposesOnlyTheLookedAtLibraryCardsAndAppliesTheSelectedPartition() {
        Game game = initAndCreateGame(); Player ai = game.getPlayers().get(1);
        Card island = addCardToZone("Island", ai, ZoneType.Library);
        Card lotus = addCardToZone("Black Lotus", ai, ZoneType.Library);
        addCardToZone("Wrath of God", ai, ZoneType.Library);
        CardCollection looked = new CardCollection(List.of(island, lotus));
        var baseline = ImmutablePair.of(new CardCollection(looked), new CardCollection());
        advisor(input -> {
            assertTrue(input.toString().contains("Black Lotus")); assertFalse(input.toString().contains("Wrath of God"));
            return input.getAsJsonArray("candidates").asList().stream().map(e -> e.getAsJsonObject())
                    .filter(e -> e.get("description").getAsString().contains("bottom of library: card-" + lotus.getId()))
                    .findFirst().orElseThrow().get("actionId").getAsString();
        });
        var chosen = LiveChoiceBridge.partition(ai, looked, baseline, false);
        assertEquals(chosen.getLeft(), new CardCollection(island)); assertEquals(chosen.getRight(), new CardCollection(lotus));
        assertEquals(baseline.getLeft().size(), 2); assertEquals(ai.getCardsIn(ZoneType.Library).size(), 3);
    }
}
