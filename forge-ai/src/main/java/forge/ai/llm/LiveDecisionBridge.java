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
        default void diagnostic(String category, String detail) { }
        default void completed(String decisionId, boolean accepted) { }
        default boolean shouldReview(Player player, JsonObject question) throws Exception { return true; }
        String choose(Player player, JsonObject question) throws Exception;
        void status(String decisionId, String message);
    }
    /** A correctable model response, distinct from provider/transport failures. */
    public static final class InvalidMoveException extends Exception {
        public InvalidMoveException(String message) { super(message); }
    }
    private static volatile Advisor advisor;
    private record Selection(Advisor owner, String id) { }
    private static final Map<SpellAbility, Selection> SELECTED = Collections.synchronizedMap(new WeakHashMap<>());
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

    static void diagnostic(String category, String detail) {
        Advisor active = advisor;
        if (active != null && active.isEnabled()) { active.diagnostic(category, detail); }
    }

    public static List<SpellAbility> choose(Player player, List<SpellAbility> baseline) {
        if (!enabled(player)) { return baseline; }
        Advisor active = advisor;
        if (active == null) { return baseline; }
        if (baseline != null && baseline.size() != 1) {
            diagnostic("Forge handled action menus", "Prepared move contains multiple abilities"); return baseline;
        }
        String decisionId = UUID.randomUUID().toString();
        try {
            String rejection = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0 && !retryAllowed(active, player)) { return baseline; }
                if (attempt > 0) { decisionId = UUID.randomUUID().toString(); }
                List<SpellAbility> currentBaseline = baseline;
                if (attempt > 0 && baseline != null && !legal(baseline.get(0), player)) { currentBaseline = null; }
                List<Option> options = options(player, currentBaseline);
                if (options.size() < 2) {
                    diagnostic("Forge handled action menus", "No bounded alternative menu"); return baseline;
                }
                String baselineId = currentBaseline == null ? "pass" : "forge";
                JsonObject question = question(player, options, baselineId, decisionId,
                        "Live hybrid decision. Choose a listed legal move or pass. Forge's prepared move may include "
                        + "choices outside the enumerated alternatives. Combat and supported resolution choices are reviewed "
                        + "separately. This menu is not exhaustive: omitted cards may still be legally playable. "
                        + "Mana payment remains Forge-controlled. Current state and listed actions override the advisory deck plan.");
                if (question == null) {
                    diagnostic("Forge handled action menus", "Snapshot outside supported scope"); return baseline;
                }
                if (attempt == 0 && !active.shouldReview(player, question)) { return baseline; }
                correction(question, rejection);
                diagnostic("Advisor consultations", "Action selection");
                try {
                    String id = active.choose(player, question);
                    if (advisor != active || !active.isEnabled()) { active.completed(decisionId, false); return baseline; }
                    Option selected = options.stream().filter(o -> o.id.equals(id)).findFirst()
                            .orElseThrow(() -> new InvalidMoveException("The selected action ID was not in the candidate list."));
                    if (player.getGame().isGameOver()) { throw new IllegalStateException("Game ended during request."); }
                    if (selected.ability != null && !legal(selected.ability, player)) {
                        throw new InvalidMoveException("The selected move failed current timing, target, or cost validation.");
                    }
                    diagnostic("Validated action selections", id.equals("forge") ? "Forge prepared move" : id.equals("pass") ? "Pass" : "Alternative");
                    active.status(decisionId, selected.ability == null ? "Luna chose to pass priority." : "Validated selection: " + selected.description);
                    if (selected.ability != null) { SELECTED.put(selected.ability, new Selection(active, decisionId)); }
                    else { active.completed(decisionId, true); }
                    return selected.ability == null ? null : List.of(selected.ability);
                } catch (InvalidMoveException e) {
                    if (attempt == 1 || !retryAllowed(active, player)) { throw e; }
                    active.completed(decisionId, false);
                    rejection = e.getMessage(); retryStatus(active, decisionId);
                }
            }
            return baseline;
        } catch (Exception e) {
            active.completed(decisionId, false);
            diagnostic("Action fallback", e.getClass().getSimpleName());
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            active.status(decisionId, "Forge fallback: " + e.getClass().getSimpleName() + ". No model move applied.");
            return baseline;
        }
    }

    private static boolean legal(SpellAbility ability, Player player) {
        SpellAbility probe = ability.copy(player);
        List<GameObject> targets = new ArrayList<>(ability.getTargets());
        probe.resetTargets();
        for (GameObject target : targets) {
            if (!legalTarget(probe, target)) { return false; }
            probe.getTargets().add(target);
        }
        return probe.canPlay() && (!probe.usesTargeting() || probe.isTargetNumberValid())
                && (probe.isLandAbility() || ComputerUtilCost.canPayCost(probe, player, false));
    }

    private static boolean supportedShape(SpellAbility ability) {
        return ability.getApi() != null && ability.getApi() != ApiType.Charm && ability.getSubAbility() == null
                && !ability.hasParam("CharmNum") && ability.getPayCosts() != null
                && !ability.getPayCosts().hasXInAnyCostPart();
    }

    private static boolean legalTarget(SpellAbility ability, GameObject target) {
        if (target instanceof SpellAbility spell) {
            // canTargetSpellAbility checks the spell's host-card properties, but assumes the
            // caller already selected a stack-targeting contract. Our mixed target pool must
            // enforce that contract explicitly, both when enumerating and before execution.
            if (!ability.usesTargeting() || !ability.getTargetRestrictions().getZone().contains(ZoneType.Stack)) {
                return false;
            }
            boolean present = false;
            for (var item : ability.getHostCard().getGame().getStack()) {
                if (item.getSpellAbility().equals(spell)) { present = true; break; }
            }
            if (!present) { return false; }
        }
        return ability.canTarget(target);
    }

    /** Broad effect coverage, bounded by structural complexity and engine-checked costs/targets. */
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
            if (card.isFaceDown() || card.isDoubleFaced()) {
                diagnostic("Alternative card evaluations omitted", card.isFaceDown() ? "Face-down source" : "Double-faced source");
                continue;
            }
            int index = 0;
            for (SpellAbility original : card.getSpellAbilities()) {
                String key = "card-" + card.getId() + "-ability-" + index++;
                boolean land = card.isInZone(ZoneType.Hand) && original.isLandAbility();
                boolean activation = card.isInZone(ZoneType.Battlefield) && original.isActivatedAbility()
                        && !original.isManaAbility() && supportedShape(original);
                boolean spell = card.isInZone(ZoneType.Hand) && original.isSpell() && supportedShape(original);
                if (!land && !activation && !spell) {
                    diagnostic("Alternative ability evaluations omitted", "Unsupported structure: " + original.getApi());
                    continue;
                }
                if (original.usesTargeting()) {
                    if (original.getMinTargets() != 1 || original.getMaxTargets() != 1 || original.isDividedAsYouChoose()
                            || original.getSubAbility() != null || original.hasParam("TargetingPlayer")) {
                        diagnostic("Alternative ability evaluations omitted", "Complex targeting or linked effects"); continue;
                    }
                    int targetIndex = 0;
                    for (GameObject target : targets) {
                        SpellAbility copy = original.copy(player);
                        copy.resetTargets();
                        if (!legalTarget(copy, target)) { targetIndex++; continue; }
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
                if (result.size() > 48) {
                    diagnostic("Forge handled action menus", "Action alternatives exceed 48"); return baselineOnly(baseline);
                }
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
        state.addProperty("viewerTurnCount", player.getTurn());
        state.addProperty("activePlayer", "p" + player.getGame().getPlayers().indexOf(player.getGame().getPhaseHandler().getPlayerTurn()));
        state.addProperty("priorityPlayer", "p" + player.getGame().getPlayers().indexOf(player.getGame().getPhaseHandler().getPriorityPlayer()));
        JsonArray candidates = new JsonArray();
        for (Option option : options) {
            JsonObject candidate = new JsonObject();
            candidate.addProperty("actionId", option.id);
            candidate.addProperty("description", option.description);
            candidate.addProperty("legalTimingNow", true);
            candidate.addProperty("payableNow", true);
            if (option.ability != null) {
                candidate.addProperty("sourceCardId", "card-" + option.ability.getHostCard().getId());
                candidate.addProperty("cost", String.valueOf(option.ability.getPayCosts()));
                candidate.addProperty("effect", String.valueOf(option.ability.getApi()));
                JsonArray targets = new JsonArray();
                for (GameObject target : option.ability.getTargets()) {
                    if (target instanceof Card card) { targets.add("card-" + card.getId()); }
                    else if (target instanceof Player p) { targets.add("p" + player.getGame().getPlayers().indexOf(p)); }
                    else if (target instanceof SpellAbility spell) { targets.add("stack-" + spell.getId()); }
                }
                candidate.add("targetIds", targets);
            }
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
            entry.addProperty("isSpell", ability.isSpell());
            entry.addProperty("effect", String.valueOf(ability.getApi()));
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

    private static boolean retryAllowed(Advisor active, Player player) {
        return advisor == active && active.isEnabled() && !player.getGame().isGameOver()
                && !Thread.currentThread().isInterrupted();
    }

    private static void correction(JsonObject question, String rejection) {
        if (rejection == null) { return; }
        JsonObject correction = new JsonObject();
        correction.addProperty("attempt", 1);
        correction.addProperty("maxRetries", 1);
        correction.addProperty("rejectionReason", rejection);
        correction.addProperty("instruction", "Your prior selection was rejected; no move was applied. Reconsider this snapshot and choose exactly one listed actionId. This is the only corrective retry.");
        question.add("correction", correction);
    }

    private static void retryStatus(Advisor active, String id) {
        diagnostic("Corrective retries", "Invalid selection; one second chance");
        active.status(id, "Selection rejected. Asking Luna once more with the current candidates.");
    }

    /** Shared bounded contract for combat and resolution choices; failures preserve the prepared baseline. */
    static String recommend(Player player, String kind, String scope, List<Option> options,
                            String baseline, JsonObject context) {
        if (!enabled(player)) { return baseline; }
        if (options.size() < 2) {
            diagnostic("Forge handled choices", kind + ": no alternative"); return baseline;
        }
        Advisor active = advisor;
        String id = UUID.randomUUID().toString();
        try {
            String rejection = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (attempt > 0 && !retryAllowed(active, player)) { return baseline; }
                if (attempt > 0) { id = UUID.randomUUID().toString(); }
                JsonObject input = question(player, options, baseline, id, scope);
                if (input == null) {
                    diagnostic("Forge handled choices", kind + ": snapshot outside supported scope"); return baseline;
                }
                input.addProperty("choiceKind", kind);
                context.addProperty("decisionId", id);
                input.add("choiceContext", context);
                if (attempt == 0 && !active.shouldReview(player, input)) { return baseline; }
                correction(input, rejection);
                diagnostic("Advisor consultations", kind);
                try {
                    String selected = active.choose(player, input);
                    if (advisor != active || !active.isEnabled() || player.getGame().isGameOver()) {
                        active.completed(id, false); return baseline;
                    }
                    if (options.stream().noneMatch(option -> option.id().equals(selected))) {
                        throw new InvalidMoveException("The selected action ID was not in the candidate list.");
                    }
                    diagnostic("Validated choices", kind + (selected.equals(baseline) ? ": Forge prepared choice" : ": alternative"));
                    active.status(id, "Validated " + kind + " selection: " + selected);
                    if (!kind.equals("declare_attackers") && !kind.equals("declare_blockers")) { active.completed(id, true); }
                    return selected;
                } catch (InvalidMoveException e) {
                    if (attempt == 1 || !retryAllowed(active, player)) { throw e; }
                    active.completed(id, false);
                    rejection = e.getMessage(); retryStatus(active, id);
                }
            }
            return baseline;
        } catch (Exception e) {
            active.completed(id, false);
            diagnostic("Choice fallback", kind + ": " + e.getClass().getSimpleName());
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            active.status(id, "Forge fallback for " + kind + ": " + e.getClass().getSimpleName());
            return baseline;
        }
    }

    static void completed(JsonObject context, boolean accepted) {
        Advisor active = advisor;
        if (active != null && context.has("decisionId")) { active.completed(context.get("decisionId").getAsString(), accepted); }
    }

    static void fallback(String kind, String reason) {
        diagnostic("Forge handled choices", kind + ": " + reason);
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
            String selected = recommend(player, "legend_rule",
                    "Legend rule: keep exactly one listed permanent. Compare counters. Other same-name legends go to the graveyard.",
                    choices, "keep-" + ((Card) normal).getId(), new JsonObject());
            if (player.getGame().isGameOver()) { return normal; }
            for (T option : options) {
                if (selected.equals("keep-" + ((Card) option).getId())) {
                    advisor.status(id, "Applied legend choice: " + selected);
                    return option;
                }
            }
            throw new IllegalArgumentException();
        } catch (Exception e) {
            advisor.completed(id, false);
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            advisor.status(id, "Forge fallback for legend choice: " + e.getClass().getSimpleName());
            return normal;
        }
    }

    public static void executed(Player player, SpellAbility ability, boolean played) {
        Selection selection = SELECTED.remove(ability);
        String decisionId = selection == null ? null : selection.id();
        if (selection != null) { selection.owner().completed(selection.id(), played); }
        if (enabled(player)) {
            diagnostic("Engine action results", (decisionId == null ? "Forge" : "Advisor-selected") + (played ? ": played" : ": declined"));
            advisor.status(decisionId == null ? "" : decisionId,
                    (decisionId == null ? "Forge: " : "Luna: ")
                            + (played ? "engine played " : "engine declined ") + ability.getHostCard().getName());
        }
    }
}
