package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.player.DelayedReveal;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;

/** Resolution prompts expose only supplied legal options; the original AI result remains the fallback. */
public final class LiveChoiceBridge {
    private static final int MAX_CHOICES = 128;
    private LiveChoiceBridge() { }
    private static final class Limit extends RuntimeException { }

    private static boolean visible(Card card, Player player) {
        return card.getView().canBeShownTo(player.getView());
    }
    private static String label(GameEntity entity) {
        if (entity == null) { return "Decline this optional choice."; }
        if (entity instanceof Card card) {
            return "card-" + card.getId() + " (" + (card.isFaceDown() ? "Face-down card" : card.getName()) + ")";
        }
        return "p" + ((Player) entity).getGame().getPlayers().indexOf(entity);
    }
    private static JsonObject context(Player player, SpellAbility ability, String prompt) {
        JsonObject result = new JsonObject(); result.addProperty("prompt", prompt);
        if (ability != null && visible(ability.getHostCard(), player)) {
            result.add("source", LandDecisionSnapshot.visibleCard(ability.getHostCard(), player));
            result.addProperty("ability", ability.toString());
        }
        return result;
    }

    public static <T extends GameEntity> T entity(Player player, Iterable<T> offered, SpellAbility ability,
                                                 String prompt, boolean optional, T baseline) {
        return entity(player, offered, ability, prompt, optional, baseline, Set.of(),
                context(player, ability, prompt), "choose_entity");
    }

    /** Only this engine search callback can authorize otherwise-hidden library cards, for this choice only. */
    public static Card zoneChange(Player player, ZoneType destination, List<ZoneType> origin, SpellAbility ability,
                                  CardCollectionView offered, DelayedReveal reveal, String prompt,
                                  boolean optional, Player decider, Card baseline) {
        if (!LiveDecisionBridge.enabled(player) || decider != player || ability == null
                || ability.getApi() != ApiType.ChangeZone || !origin.contains(ZoneType.Library)) { return baseline; }
        Set<Card> lookedAt = new HashSet<>();
        if (reveal != null && reveal.getZone().contains(ZoneType.Library) && !ability.hasParam("NoLooking")) {
            for (Card card : offered) {
                if (card.isInZone(ZoneType.Library) && card.getOwner().getView().equals(reveal.getOwner())
                        && reveal.getCards().contains(card.getView())) { lookedAt.add(card); }
            }
        }
        List<Card> choices = new ArrayList<>();
        for (Card card : offered) { choices.add(card); }
        // Never transmit the order of the shuffled library, including when the engine supplied an ordered pool.
        choices.sort(Comparator.comparing((Card card) -> card.getName()).thenComparingInt(Card::getId));
        JsonObject context = context(player, ability, prompt);
        context.addProperty("destination", destination.name());
        context.addProperty("informationPolicy", "Only the engine's legal offered search candidates are shown. Hidden identities require this search's explicit reveal permission. Candidates are sorted independently of library order; no other library cards are exposed.");
        return entity(player, choices, ability, prompt, optional, baseline, lookedAt, context, "library_search");
    }

    private static <T extends GameEntity> T entity(Player player, Iterable<T> offered, SpellAbility ability,
                                                  String prompt, boolean optional, T baseline, Set<Card> lookedAt,
                                                  JsonObject context, String kind) {
        if (!LiveDecisionBridge.enabled(player) || (baseline == null && !optional)) { return baseline; }
        List<T> choices = new ArrayList<>(); choices.add(baseline);
        JsonArray cards = new JsonArray();
        boolean containsBaseline = baseline == null;
        for (T option : offered) {
            if (option instanceof Card card) {
                if (!visible(card, player) && !lookedAt.contains(card)) {
                    LiveDecisionBridge.diagnostic("Forge handled choices", "Single entity: offered identity is hidden"); return baseline;
                }
                cards.add(LandDecisionSnapshot.visibleCard(card, player));
            }
            if (option.equals(baseline)) { containsBaseline = true; }
            else { choices.add(option); }
            if (choices.size() > MAX_CHOICES) {
                LiveDecisionBridge.diagnostic("Forge handled choices", "Single entity: more than 128 choices"); return baseline;
            }
        }
        if (!containsBaseline) { return baseline; }
        if (optional && baseline != null) { choices.add(null); }
        context.add("offeredCards", cards);
        List<LiveDecisionBridge.Option> options = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            options.add(new LiveDecisionBridge.Option(i == 0 ? "forge" : "entity-" + i, label(choices.get(i)), null));
        }
        String selected = LiveDecisionBridge.recommend(player, kind,
                "Choose exactly one of the offered legal entities for this resolution prompt. Declining is available only "
                + "when explicitly listed. Offered cards are visible to the chooser.", options, "forge", context);
        return selected.equals("forge") ? baseline : choices.get(Integer.parseInt(selected.substring(7)));
    }

    public static CardCollectionView cards(Player player, CardCollectionView offered, int min, int max,
                                           SpellAbility ability, String prompt, CardCollectionView baseline) {
        if (!LiveDecisionBridge.enabled(player) || baseline == null || min < 0 || max < min) { return baseline; }
        List<Card> pool = new ArrayList<>(); JsonArray cardContext = new JsonArray();
        for (Card card : offered) {
            if (!visible(card, player)) {
                LiveDecisionBridge.diagnostic("Forge handled choices", "Card set: offered identity is hidden"); return baseline;
            }
            pool.add(card); cardContext.add(LandDecisionSnapshot.visibleCard(card, player));
        }
        if (pool.size() > 16 || !pool.containsAll(baseline) || baseline.size() < min || baseline.size() > max) {
            LiveDecisionBridge.diagnostic("Forge handled choices", "Card set: pool exceeds 16 or baseline is outside supplied bounds");
            return baseline;
        }
        List<CardCollectionView> choices = new ArrayList<>(); choices.add(baseline);
        try { subsets(pool, 0, min, Math.min(max, pool.size()), new CardCollection(), choices); }
        catch (Limit e) { LiveDecisionBridge.fallback(prompt, "too many card combinations"); return baseline; }
        List<LiveDecisionBridge.Option> options = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            options.add(new LiveDecisionBridge.Option(i == 0 ? "forge" : "cards-" + i,
                    choices.get(i).isEmpty() ? "Choose no cards." : describe(choices.get(i)), null));
        }
        JsonObject context = context(player, ability, prompt); context.add("offeredCards", cardContext);
        context.addProperty("minimum", min); context.addProperty("maximum", max);
        String selected = LiveDecisionBridge.recommend(player, "choose_cards",
                "Choose the listed set of cards for this prompt. Each candidate obeys the supplied minimum and maximum; "
                + "the cards are drawn only from the engine's legal offered pool.", options, "forge", context);
        return selected.equals("forge") ? baseline : choices.get(Integer.parseInt(selected.substring(6)));
    }

    private static void subsets(List<Card> pool, int index, int min, int max, CardCollection chosen,
                                 List<CardCollectionView> choices) {
        if (chosen.size() > max || chosen.size() + pool.size() - index < min) { return; }
        if (index == pool.size()) {
            if (choices.stream().noneMatch(c -> c.size() == chosen.size() && c.containsAll(chosen))) {
                if (choices.size() >= MAX_CHOICES) { throw new Limit(); }
                choices.add(new CardCollection(chosen));
            }
            return;
        }
        subsets(pool, index + 1, min, max, chosen, choices);
        chosen.add(pool.get(index)); subsets(pool, index + 1, min, max, chosen, choices); chosen.remove(pool.get(index));
    }

    public static ImmutablePair<CardCollection, CardCollection> partition(Player player, CardCollection cards,
            ImmutablePair<CardCollection, CardCollection> baseline, boolean surveil) {
        if (!LiveDecisionBridge.enabled(player)) { return baseline; }
        if (cards.isEmpty() || cards.size() > 6) {
            LiveDecisionBridge.diagnostic("Forge handled choices", (surveil ? "Surveil" : "Scry") + ": zero cards or more than six");
            return baseline;
        }
        List<ImmutablePair<CardCollection, CardCollection>> choices = new ArrayList<>(); choices.add(baseline);
        List<LiveDecisionBridge.Option> options = new ArrayList<>();
        String destination = surveil ? "graveyard" : "bottom of library";
        options.add(new LiveDecisionBridge.Option("forge", partitionDescription(baseline, destination), null));
        for (int mask = 0; mask < (1 << cards.size()); mask++) {
            CardCollection top = new CardCollection(), rest = new CardCollection();
            for (int i = 0; i < cards.size(); i++) { ((mask & (1 << i)) == 0 ? top : rest).add(cards.get(i)); }
            if (top.equals(baseline.getLeft()) && rest.equals(baseline.getRight())) { continue; }
            var choice = ImmutablePair.of(top, rest); choices.add(choice);
            options.add(new LiveDecisionBridge.Option("pile-" + (choices.size() - 1), partitionDescription(choice, destination), null));
        }
        JsonObject context = new JsonObject(); JsonArray revealed = new JsonArray();
        // These exact cards have been looked at by this player for scry/surveil, even though their zone is Library.
        for (Card card : cards) { revealed.add(LandDecisionSnapshot.visibleCard(card, player)); }
        context.add("lookedAtCards", revealed);
        String selected = LiveDecisionBridge.recommend(player, surveil ? "surveil" : "scry",
                "Choose which of the looked-at cards to keep on top and which to move to the " + destination
                + ". Each pile's listed order is preserved. Current game state takes precedence over the deck plan.", options, "forge", context);
        return selected.equals("forge") ? baseline : choices.get(Integer.parseInt(selected.substring(5)));
    }

    private static String partitionDescription(ImmutablePair<CardCollection, CardCollection> choice, String destination) {
        return "Keep on top in listed order: " + describe(choice.getLeft()) + "; move to " + destination + ": " + describe(choice.getRight());
    }
    private static String describe(Iterable<Card> cards) {
        List<String> labels = new ArrayList<>(); for (Card card : cards) { labels.add(label(card)); }
        return labels.isEmpty() ? "none" : String.join(", ", labels);
    }
}
