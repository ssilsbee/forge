package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.ComputerUtilCost;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Opt-in live adapter. All game-object access and final validation remain on the game thread. */
public final class LiveDecisionBridge {
    public interface Advisor {
        default boolean isEnabled() { return true; }
        default void prepare(Player player) { }
        String choose(Player player, JsonObject question) throws Exception;
        void status(String decisionId, String message);
    }
    private static volatile Advisor advisor;
    private static final Map<SpellAbility, String> SELECTED = Collections.synchronizedMap(new WeakHashMap<>());
    private LiveDecisionBridge() { }
    public static void install(Advisor value) { advisor = value; }

    static boolean enabled(Player player) {
        return supported(player) && player.getGame().getAge() == GameStage.Play;
    }

    private static boolean supported(Player player) {
        return advisor != null && advisor.isEnabled() && player.getGame().getMaingame() == null
                && !player.getGame().isNoGUIUser() && !player.getGame().isGameOver()
                && player.getGame().getRules().getGameType() == GameType.Constructed
                && player.getGame().getPlayers().size() == 2;
    }

    public static void prepare(Player player) {
        if (!supported(player)) { return; }
        Advisor active = advisor;
        try { active.prepare(player); }
        catch (RuntimeException e) {
            active.status("", "Early deck preparation unavailable; LLM play will prepare on its first decision.");
        }
    }

    record Option(String id, String description, SpellAbility ability) { }

    public static List<SpellAbility> choose(Player player, List<SpellAbility> baseline) {
        if (!enabled(player)) { return baseline; }
        if (baseline != null && baseline.size() != 1) { return baseline; }
        String decisionId = UUID.randomUUID().toString();
        try {
            List<Option> options = options(player, baseline);
            if (options.size() < 2) { return baseline; }
            String baselineId = baseline == null ? "pass" : "forge";
            JsonObject question = question(player, options, baselineId, decisionId,
                    "Live hybrid decision. Choose a listed legal move or pass. Forge's prepared move may include "
                    + "choices outside the enumerated alternatives. Combat and supported resolution choices are reviewed "
                    + "separately. Mana payment remains Forge-controlled. Current state and legal actions override the advisory deck plan.");
            if (question == null) { return baseline; }
            String id = advisor.choose(player, question);
            Option selected = options.stream().filter(o -> o.id.equals(id)).findFirst().orElseThrow();
            if (player.getGame().isGameOver()) { throw new IllegalStateException("Game ended during request."); }
            if (selected.ability != null && !legal(selected.ability, player)) { throw new IllegalStateException("Move became unavailable."); }
            advisor.status(decisionId, selected.ability == null ? "Luna chose to pass priority." : "Validated selection: " + selected.description);
            if (selected.ability != null) { SELECTED.put(selected.ability, decisionId); }
            return selected.ability == null ? null : List.of(selected.ability);
        } catch (Exception e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            advisor.status(decisionId, "Forge fallback: " + e.getClass().getSimpleName() + ". No model move applied.");
            return baseline;
        }
    }

    private static boolean legal(SpellAbility ability, Player player) {
        SpellAbility probe = ability.copy(player);
        List<GameObject> targets = new ArrayList<>(ability.getTargets());
        probe.resetTargets();
        for (GameObject target : targets) {
            if (!probe.canTarget(target)) { return false; }
            probe.getTargets().add(target);
        }
        return probe.canPlay() && (!probe.usesTargeting() || probe.isTargetNumberValid())
                && (probe.isLandAbility() || ComputerUtilCost.canPayCost(probe, player, false));
    }

    /** Conservative families only: normal lands, simple targetless spells, and a few one-target APIs. */
    static List<Option> options(Player player, List<SpellAbility> baseline) {
        List<Option> result = new ArrayList<>();
        if (baseline != null) { result.add(new Option("forge", "Forge prepared move: " + baseline.get(0).getHostCard().getName()
                + " — " + baseline.get(0), baseline.get(0))); }
        List<GameObject> targets = new ArrayList<>();
        for (Card card : player.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (card.getView().canBeShownTo(player.getView()) && !card.isFaceDown()) { targets.add(card); }
        }
        targets.addAll(player.getGame().getPlayers());
        for (var item : player.getGame().getStack()) { targets.add(item.getSpellAbility()); }
        List<Card> sources = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
        sources.addAll(player.getCardsIn(ZoneType.Battlefield));
        for (Card card : sources) {
            if (card.isFaceDown() || card.isDoubleFaced()) { continue; }
            int index = 0;
            for (SpellAbility original : card.getSpellAbilities()) {
                String key = "card-" + card.getId() + "-ability-" + index++;
                boolean land = card.isInZone(ZoneType.Hand) && original.isLandAbility();
                boolean combo = card.isInZone(ZoneType.Battlefield) && original.isActivatedAbility()
                        && (original.getApi() == ApiType.Clone || original.getApi() == ApiType.RemoveCounter);
                boolean spell = card.isInZone(ZoneType.Hand) && original.isSpell()
                        && (original.getApi() == ApiType.Counter || original.getApi() == ApiType.DealDamage
                        || original.getApi() == ApiType.ChangeZone);
                // Targetless permanent spells use the ordinary printed cost; other resolution choices stay Forge's.
                boolean permanent = card.isInZone(ZoneType.Hand) && card.isPermanent() && original.isSpell()
                        && !original.usesTargeting() && original.getSubAbility() == null
                        && !original.hasParam("CharmNum") && !card.getManaCost().toString().contains("X");
                if (!land && !combo && !spell && !permanent) { continue; }
                if (original.usesTargeting()) {
                    if (original.getMinTargets() != 1 || original.getMaxTargets() != 1 || original.isDividedAsYouChoose()
                            || original.getSubAbility() != null || original.hasParam("TargetingPlayer")) { continue; }
                    int targetIndex = 0;
                    for (GameObject target : targets) {
                        SpellAbility copy = original.copy(player);
                        copy.resetTargets();
                        if (!copy.canTarget(target)) { targetIndex++; continue; }
                        copy.getTargets().add(target);
                        if (copy.canPlay() && ComputerUtilCost.canPayCost(copy, player, false)) {
                            String targetName = target instanceof Card c ? "card-" + c.getId() + " (" + c.getName() + ")"
                                    : target instanceof Player p ? "p" + player.getGame().getPlayers().indexOf(p)
                                    : "stack-" + ((SpellAbility) target).getId() + " (" + ((SpellAbility) target).getHostCard().getName() + ")";
                            result.add(new Option(key + "-target-" + targetIndex, copy + "; cost: " + copy.getPayCosts()
                                    + "; target: " + targetName, copy));
                        }
                        targetIndex++;
                    }
                } else {
                    SpellAbility copy = original.copy(player);
                    if (legal(copy, player)) { result.add(new Option(key, land ? "Play " + card.getName() : copy + "; cost: " + copy.getPayCosts(), copy)); }
                }
                if (result.size() > 48) { return baselineOnly(baseline); }
            }
        }
        result.add(new Option("pass", "Pass priority.", null));
        return result;
    }

    private static List<Option> baselineOnly(List<SpellAbility> baseline) {
        // Oversized action sets do not silently lose alternatives or produce a network request.
        return List.of(new Option("forge", "Forge handles this position.", baseline == null ? null : baseline.get(0)));
    }

    private static JsonObject question(Player player, List<Option> options, String baseline, String id, String scope) {
        JsonObject state = LandDecisionSnapshot.visibleState(player);
        if (state == null) { return null; }
        state.addProperty("schemaVersion", 1);
        state.addProperty("decisionType", "live_action");
        state.addProperty("decisionId", id);
        state.addProperty("scope", scope);
        state.addProperty("baselineActionId", baseline);
        state.addProperty("activePlayer", "p" + player.getGame().getPlayers().indexOf(player.getGame().getPhaseHandler().getPlayerTurn()));
        state.addProperty("priorityPlayer", "p" + player.getGame().getPlayers().indexOf(player.getGame().getPhaseHandler().getPriorityPlayer()));
        JsonArray candidates = new JsonArray();
        for (Option option : options) {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("actionId", option.id);
            candidate.addProperty("description", option.description);
            candidate.addProperty("legalTimingNow", true);
            candidate.addProperty("payableNow", true);
            candidates.add(candidate);
        }
        state.add("candidates", candidates);
        JsonArray stack = new JsonArray();
        for (var item : player.getGame().getStack()) {
            SpellAbility ability = item.getSpellAbility();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "stack-" + ability.getId());
            entry.addProperty("source", ability.getHostCard().getName());
            entry.addProperty("controller", "p" + player.getGame().getPlayers().indexOf(ability.getActivatingPlayer()));
            entry.addProperty("description", ability.toString());
            entry.addProperty("oracleText", ability.getHostCard().getOracleText());
            JsonArray targets = new JsonArray();
            for (GameObject target : ability.getTargets()) {
                if (target instanceof Card card) { targets.add("card-" + card.getId()); }
                else if (target instanceof Player p) { targets.add("p" + player.getGame().getPlayers().indexOf(p)); }
                else if (target instanceof SpellAbility spell) { targets.add("stack-" + spell.getId()); }
            }
            entry.add("targetIds", targets);
            stack.add(entry);
        }
        state.add("stack", stack);
        if (player.getGame().getCombat() != null) {
            state.add("combat", LiveCombatBridge.snapshot(player.getGame().getCombat()));
        }
        return state;
    }

    /** Shared bounded contract for combat and resolution choices; failures preserve the prepared baseline. */
    static String recommend(Player player, String kind, String scope, List<Option> options,
                            String baseline, JsonObject context) {
        if (!enabled(player) || options.size() < 2) { return baseline; }
        Advisor active = advisor;
        String id = UUID.randomUUID().toString();
        try {
            JsonObject input = question(player, options, baseline, id, scope);
            if (input == null) { return baseline; }
            input.addProperty("choiceKind", kind);
            input.add("choiceContext", context);
            String selected = active.choose(player, input);
            if (advisor != active || !active.isEnabled() || player.getGame().isGameOver()) { return baseline; }
            if (options.stream().noneMatch(option -> option.id().equals(selected))) {
                throw new IllegalArgumentException("Unlisted action ID.");
            }
            active.status(id, "Validated " + kind + " selection: " + selected);
            return selected;
        } catch (Exception e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            active.status(id, "Forge fallback for " + kind + ": " + e.getClass().getSimpleName());
            return baseline;
        }
    }

    static void fallback(String kind, String reason) {
        Advisor active = advisor;
        if (active != null && active.isEnabled()) { active.status("", "Forge handles " + kind + ": " + reason); }
    }

    public static <T extends GameEntity> T legend(Player player, FCollectionView<T> options, SpellAbility ability, T normal) {
        if (!enabled(player) || ability.getApi() != ApiType.InternalLegendaryRule || options.size() < 2) { return normal; }
        String id = UUID.randomUUID().toString();
        try {
            List<Option> choices = new ArrayList<>();
            for (T option : options) {
                if (!(option instanceof Card card)) { return normal; }
                choices.add(new Option("keep-" + card.getId(), "Keep card-" + card.getId() + " (" + card.getName()
                        + "); other same-name legends go to the graveyard.", null));
            }
            JsonObject question = question(player, choices, "keep-" + ((Card) normal).getId(), id,
                    "Legend rule: keep exactly one listed permanent. Compare counters. Other same-name legends go to the graveyard.");
            if (question == null) { return normal; }
            String selected = advisor.choose(player, question);
            if (player.getGame().isGameOver()) { return normal; }
            for (T option : options) {
                if (selected.equals("keep-" + ((Card) option).getId())) {
                    advisor.status(id, "Applied legend choice: " + selected);
                    return option;
                }
            }
            throw new IllegalArgumentException();
        } catch (Exception e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            advisor.status(id, "Forge fallback for legend choice: " + e.getClass().getSimpleName());
            return normal;
        }
    }

    public static void executed(Player player, SpellAbility ability, boolean played) {
        String decisionId = SELECTED.remove(ability);
        if (enabled(player)) {
            advisor.status(decisionId == null ? "" : decisionId,
                    (decisionId == null ? "Forge: " : "Luna: ")
                            + (played ? "engine played " : "engine declined ") + ability.getHostCard().getName());
        }
    }
}
