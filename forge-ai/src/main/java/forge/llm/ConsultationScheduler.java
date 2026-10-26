package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Local, bounded consultation rules. No live game objects or extra model calls. */
final class ConsultationScheduler {
    static final Set<String> RULES = Set.of("opponent_spell", "opponent_ability", "counterspell_window",
            "opponent_end_step", "own_second_main", "opponent_board_change", "every_action_window");
    record Verdict(boolean consult, String reason) { }
    private static final class State {
        int lastOwnTurn = -1, lastTurn = -1, plannedTurn = -1, lastOwnTurnCount = -1;
        Set<String> watch; // null uses conservative defaults until the model supplies a policy
        Set<String> hand;
        String opposingBoard;
        String lastObserved;
        final Set<String> reviewed = new LinkedHashSet<>();
    }
    private static final class Pending {
        String player, fingerprint;
        int turn;
        boolean ownMain;
        Set<String> watch;
    }
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    private State state(String player) {
        State state = states.get(player);
        if (state == null) {
            state = new State(); states.put(player, state);
            while (states.size() > 16) {
                String old = states.keySet().iterator().next(); states.remove(old);
                pending.entrySet().removeIf(e -> e.getValue().player.equals(old));
            }
        }
        return state;
    }
    private static boolean ownTurn(JsonObject q) { return q.get("viewer").equals(q.get("activePlayer")); }
    private static boolean ownMain(JsonObject q) {
        return ownTurn(q) && Set.of("MAIN1", "MAIN2").contains(q.get("phase").getAsString());
    }
    private static JsonArray array(JsonObject q, String name) { return q.has(name) ? q.getAsJsonArray(name) : new JsonArray(); }
    private static Set<String> hand(JsonObject q) {
        Set<String> hand = new LinkedHashSet<>();
        for (JsonElement p : array(q, "players")) {
            JsonObject player = p.getAsJsonObject();
            if (!player.get("id").equals(q.get("viewer"))) { continue; }
            for (JsonElement card : array(player, "hand")) { hand.add(card.getAsJsonObject().get("id").getAsString()); }
        }
        return hand;
    }
    private static String opposingBoard(JsonObject q) {
        JsonArray board = new JsonArray();
        for (JsonElement p : array(q, "players")) {
            JsonObject player = p.getAsJsonObject();
            if (!player.get("id").equals(q.get("viewer"))) { board.add(array(player, "Battlefield")); }
        }
        return board.toString();
    }
    private static String fingerprint(JsonObject q) throws IOException {
        JsonObject state = q.deepCopy();
        for (String key : Set.of("decisionId", "consultationContext", "consultationPolicyRequested",
                "strategyMemory", "strategyNotesRequested", "deckStrategy")) { state.remove(key); }
        return DeckStrategyPlanner.digest(state.toString());
    }
    private static boolean available(JsonObject q, String effect) {
        for (JsonElement c : array(q, "candidates")) {
            JsonObject candidate = c.getAsJsonObject();
            if (candidate.has("effect") && effect.equals(candidate.get("effect").getAsString())) { return true; }
        }
        return false;
    }
    private static boolean opposingStack(JsonObject q, Boolean spell) {
        for (JsonElement e : array(q, "stack")) {
            JsonObject entry = e.getAsJsonObject();
            if (entry.get("controller").equals(q.get("viewer"))) { continue; }
            if (spell == null || !entry.has("isSpell") || entry.get("isSpell").getAsBoolean() == spell) { return true; }
        }
        return false;
    }
    private static boolean dangerousStack(JsonObject q) {
        Set<String> ours = new LinkedHashSet<>(); ours.add(q.get("viewer").getAsString());
        for (JsonElement p : array(q, "players")) {
            JsonObject player = p.getAsJsonObject();
            if (player.get("id").equals(q.get("viewer"))) {
                for (JsonElement c : array(player, "Battlefield")) { ours.add(c.getAsJsonObject().get("id").getAsString()); }
            }
        }
        for (JsonElement e : array(q, "stack")) {
            JsonObject entry = e.getAsJsonObject();
            if (entry.get("controller").equals(q.get("viewer"))) { continue; }
            if (entry.has("effect") && Set.of("DestroyAll", "ChangeZoneAll", "DamageAll", "DealDamageAll")
                    .contains(entry.get("effect").getAsString())) { return true; }
            for (JsonElement target : array(entry, "targetIds")) { if (ours.contains(target.getAsString())) { return true; } }
        }
        return false;
    }

    synchronized Verdict consider(String player, JsonObject q) throws IOException {
        State state = state(player);
        int turn = q.get("turn").getAsInt();
        int ownTurnCount = q.has("viewerTurnCount") ? q.get("viewerTurnCount").getAsInt() : -1;
        // The AI may have had no supported action windows during an entire own turn.
        // Its engine turn counter still expires policies before the next opposing window.
        if (turn < state.lastTurn || ownTurnCount != state.lastOwnTurnCount
                || (ownTurn(q) && turn != state.lastOwnTurn)) {
            state.watch = null; state.reviewed.clear(); state.plannedTurn = -1;
            state.lastObserved = null;
            pending.entrySet().removeIf(e -> e.getValue().player.equals(player));
            if (ownTurn(q)) { state.lastOwnTurn = turn; }
        }
        state.lastTurn = turn;
        state.lastOwnTurnCount = ownTurnCount;
        String fingerprint = fingerprint(q);
        boolean unchanged = fingerprint.equals(state.lastObserved);
        state.lastObserved = fingerprint;
        Set<String> hand = hand(q);
        boolean newCard = state.hand != null && !state.hand.containsAll(hand);
        state.hand = hand;
        String board = opposingBoard(q);
        boolean changedBoard = state.opposingBoard != null && !state.opposingBoard.equals(board);
        state.opposingBoard = board;
        if (q.has("choiceKind")) { return new Verdict(true, "Combat or resolution choice: " + q.get("choiceKind").getAsString()); }
        if (!"pass".equals(q.get("baselineActionId").getAsString())) { return new Verdict(true, "Forge proposes an action"); }
        if (ownMain(q) && state.plannedTurn != turn) { return new Verdict(true, "First useful main-phase decision this turn"); }
        if (unchanged && state.reviewed.contains(fingerprint)) { return new Verdict(false, "Already reviewed this unchanged action window"); }
        if (newCard) { return new Verdict(true, "New card in the AI hand"); }
        if (dangerousStack(q)) { return new Verdict(true, "Opponent targets the AI or threatens the board"); }
        Set<String> watch = state.watch == null ? Set.of("counterspell_window", "opponent_end_step") : state.watch;
        if (watch.contains("every_action_window")) { return new Verdict(true, "Model requested every changed action window"); }
        if (watch.contains("counterspell_window") && opposingStack(q, null) && available(q, "Counter")) {
            return new Verdict(true, "Requested counterspell window with a legal counter");
        }
        if (watch.contains("opponent_spell") && opposingStack(q, true)) { return new Verdict(true, "Model requested opponent spell review"); }
        if (watch.contains("opponent_ability") && opposingStack(q, false)) { return new Verdict(true, "Model requested opponent ability review"); }
        if (watch.contains("opponent_end_step") && !ownTurn(q) && "END_OF_TURN".equals(q.get("phase").getAsString())
                && array(q, "stack").isEmpty() && (state.watch != null || available(q, "Draw"))) {
            return new Verdict(true, "Requested opponent end-step window");
        }
        if (watch.contains("own_second_main") && ownTurn(q) && "MAIN2".equals(q.get("phase").getAsString())) {
            return new Verdict(true, "Model requested postcombat main phase");
        }
        if (watch.contains("opponent_board_change") && !ownTurn(q) && changedBoard) {
            return new Verdict(true, "Model requested review of an opposing board change");
        }
        return new Verdict(false, "Forge passes; no consultation condition matched");
    }

    synchronized void register(String player, JsonObject q) throws IOException {
        State state = state(player);
        Pending request = new Pending(); request.player = player; request.fingerprint = fingerprint(q);
        request.turn = q.get("turn").getAsInt(); request.ownMain = ownMain(q) && !q.has("choiceKind");
        pending.put(q.get("decisionId").getAsString(), request);
        while (pending.size() > 64) { pending.remove(pending.keySet().iterator().next()); }
        JsonObject context = q.has("consultationContext") ? q.getAsJsonObject("consultationContext") : new JsonObject();
        if (!context.has("reason")) { context.addProperty("reason", "Explicit decision request"); }
        JsonArray supported = new JsonArray(); RULES.stream().sorted().forEach(supported::add);
        context.add("supportedWatchRules", supported);
        context.addProperty("hasExplicitPolicy", state.watch != null);
        JsonArray current = new JsonArray();
        (state.watch == null ? Set.of("counterspell_window", "opponent_end_step") : state.watch).stream().sorted().forEach(current::add);
        context.add("currentWatchRules", current);
        context.addProperty("expiry", "Start of the AI's next own turn. Mandatory reviews cannot be disabled.");
        q.add("consultationContext", context); q.addProperty("consultationPolicyRequested", true);
    }

    static JsonObject validate(JsonElement value) {
        if (!value.isJsonObject()) { throw new IllegalArgumentException("Expected a consultation policy object"); }
        JsonObject policy = value.getAsJsonObject();
        if (policy.size() != 1 || !policy.has("watch") || !policy.get("watch").isJsonArray()
                || policy.getAsJsonArray("watch").size() > RULES.size()) { throw new IllegalArgumentException("Expected a bounded watch list"); }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement rule : policy.getAsJsonArray("watch")) {
            if (!rule.isJsonPrimitive() || !rule.getAsJsonPrimitive().isString()
                    || !RULES.contains(rule.getAsString()) || !seen.add(rule.getAsString())) {
                throw new IllegalArgumentException("Unsupported or duplicate consultation rule");
            }
        }
        return policy.deepCopy();
    }

    synchronized void propose(String id, JsonObject policy) {
        Pending request = pending.get(id);
        if (request == null) { return; }
        Set<String> watch = new LinkedHashSet<>();
        for (JsonElement rule : validate(policy).getAsJsonArray("watch")) { watch.add(rule.getAsString()); }
        request.watch = watch;
    }

    synchronized boolean complete(String id, boolean accepted) {
        Pending request = pending.remove(id);
        if (request == null || !accepted) { return false; }
        State state = state(request.player);
        if (state.lastOwnTurn > request.turn) { return false; }
        state.reviewed.add(request.fingerprint);
        while (state.reviewed.size() > 64) { state.reviewed.remove(state.reviewed.iterator().next()); }
        if (request.ownMain) { state.plannedTurn = request.turn; }
        if (request.watch != null) { state.watch = request.watch; return true; }
        return false;
    }
}
