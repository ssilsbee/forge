package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.testng.annotations.Test;
import java.io.IOException;
import java.util.UUID;
import static org.testng.Assert.*;

public class ConsultationSchedulerTest {
    private JsonObject question() throws IOException {
        JsonObject q = LlmHttp.object("{\"turn\":1,\"phase\":\"MAIN1\",\"viewer\":\"p0\",\"activePlayer\":\"p0\","
                + "\"baselineActionId\":\"pass\",\"candidates\":[{\"actionId\":\"draw\",\"effect\":\"Draw\"},{\"actionId\":\"pass\"}],"
                + "\"players\":[{\"id\":\"p0\",\"hand\":[{\"id\":\"card-1\"}],\"Battlefield\":[{\"id\":\"card-2\"}]},"
                + "{\"id\":\"p1\",\"handCount\":1,\"Battlefield\":[{\"id\":\"card-3\"}]}],\"stack\":[]}");
        q.addProperty("decisionId", UUID.randomUUID().toString()); return q;
    }
    private JsonObject next(JsonObject q, String phase, boolean own, int turn) {
        JsonObject next = q.deepCopy(); next.addProperty("decisionId", UUID.randomUUID().toString());
        next.addProperty("phase", phase); next.addProperty("activePlayer", own ? "p0" : "p1"); next.addProperty("turn", turn); return next;
    }
    private JsonObject policy(String... rules) {
        JsonObject p = new JsonObject(); JsonArray watch = new JsonArray(); for (String rule : rules) { watch.add(rule); }
        p.add("watch", watch); return p;
    }
    private void accept(ConsultationScheduler s, String key, JsonObject q, JsonObject policy) throws IOException {
        assertTrue(s.consider(key, q).consult()); s.register(key, q);
        if (policy != null) { s.propose(q.get("decisionId").getAsString(), policy); }
        s.complete(q.get("decisionId").getAsString(), true);
    }
    private void stack(JsonObject q, String effect, boolean spell, String... targets) {
        JsonObject entry = new JsonObject(); entry.addProperty("id", "stack-1"); entry.addProperty("controller", "p1");
        entry.addProperty("effect", effect); entry.addProperty("isSpell", spell);
        JsonArray ids = new JsonArray(); for (String target : targets) { ids.add(target); } entry.add("targetIds", ids);
        JsonArray stack = new JsonArray(); stack.add(entry); q.add("stack", stack);
    }

    @Test public void aggroPolicySkipsIdlePhasesButKeepsNextTurnsPlanning() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "aggro", q, policy());
        assertFalse(s.consider("aggro", next(q, "MAIN1", true, 1)).consult());
        assertFalse(s.consider("aggro", next(q, "COMBAT_BEGIN", true, 1)).consult());
        assertFalse(s.consider("aggro", next(q, "END_OF_TURN", false, 2)).consult());
        assertTrue(s.consider("aggro", next(q, "MAIN1", true, 3)).consult());
    }

    @Test public void forgeActionsAndResolutionChoicesCannotBeSuppressed() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "ai", q, policy());
        JsonObject action = next(q, "MAIN1", false, 2); action.addProperty("baselineActionId", "forge");
        assertTrue(s.consider("ai", action).consult());
        for (String kind : new String[]{"declare_attackers", "declare_blockers", "library_search", "choose_cards", "scry", "legend_rule"}) {
            JsonObject choice = next(q, "MAIN1", false, 2); choice.addProperty("choiceKind", kind);
            assertTrue(s.consider("ai", choice).consult(), kind);
        }
    }

    @Test public void controlPolicyWakesOnlyWhenALegalCounterIsAvailable() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "control", q, policy("counterspell_window"));
        JsonObject response = next(q, "MAIN1", false, 2); stack(response, "PermanentCreature", true);
        assertFalse(s.consider("control", response).consult());
        response.getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("effect", "Counter");
        assertTrue(s.consider("control", response).consult());
    }

    @Test public void targetedThreatsAndBoardWipesOverrideQuietPolicies() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "ai", q, policy());
        JsonObject threat = next(q, "MAIN1", false, 2); stack(threat, "Destroy", true, "card-2");
        assertTrue(s.consider("ai", threat).consult());
        JsonObject wipe = next(q, "MAIN1", false, 2); stack(wipe, "DestroyAll", true);
        assertTrue(s.consider("ai", wipe).consult());
    }

    @Test public void newCardsForceReconsiderationWithoutWakingOnHandDecreases() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "ai", q, policy());
        JsonObject reduced = next(q, "MAIN1", true, 1);
        reduced.getAsJsonArray("players").get(0).getAsJsonObject().add("hand", new JsonArray());
        assertFalse(s.consider("ai", reduced).consult());
        JsonObject drawn = next(q, "MAIN1", true, 1);
        assertTrue(s.consider("ai", drawn).consult());
    }

    @Test public void unchangedWindowsAreSkippedEvenWithThoroughPolicyAndNewDecisionIds() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "ai", q, policy("every_action_window"));
        assertFalse(s.consider("ai", next(q, "MAIN1", true, 1)).consult());
        JsonObject changed = next(q, "MAIN2", true, 1);
        assertTrue(s.consider("ai", changed).consult());
    }

    @Test public void policyExpiresAtNextOwnTurnAndDoesNotLeakBetweenPlayers() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "one-game", q, policy("every_action_window"));
        assertFalse(s.consider("another-game", next(q, "COMBAT_BEGIN", true, 1)).consult());
        JsonObject nextTurn = next(q, "MAIN1", true, 3); accept(s, "one-game", nextTurn, null);
        assertFalse(s.consider("one-game", next(nextTurn, "MAIN2", true, 3)).consult());
    }

    @Test public void rejectedPoliciesNeverTakeEffect() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question();
        assertTrue(s.consider("ai", q).consult()); s.register("ai", q); s.propose(q.get("decisionId").getAsString(), policy("every_action_window"));
        s.complete(q.get("decisionId").getAsString(), false);
        assertFalse(s.consider("ai", next(q, "COMBAT_BEGIN", true, 1)).consult());
    }

    @Test public void policyExpiresEvenWhenAnEntireOwnTurnHadNoActionWindows() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question();
        q.addProperty("viewerTurnCount", 1); accept(s, "ai", q, policy("every_action_window"));
        JsonObject later = next(q, "MAIN1", false, 4); later.addProperty("viewerTurnCount", 2);
        assertFalse(s.consider("ai", later).consult());
    }

    @Test public void requestedEndStepAndBoardChangesAreHonored() throws Exception {
        ConsultationScheduler s = new ConsultationScheduler(); JsonObject q = question(); accept(s, "ai", q, policy("opponent_end_step", "opponent_board_change"));
        assertTrue(s.consider("ai", next(q, "END_OF_TURN", false, 2)).consult());
        JsonObject changed = next(q, "MAIN1", false, 2);
        JsonObject card = new JsonObject(); card.addProperty("id", "card-4");
        changed.getAsJsonArray("players").get(1).getAsJsonObject().getAsJsonArray("Battlefield").add(card);
        assertTrue(s.consider("ai", changed).consult());
    }

    @Test public void unsupportedDuplicateAndExcessiveRulesAreRejected() throws Exception {
        assertEquals(ConsultationScheduler.validate(policy()), policy());
        assertThrows(IllegalArgumentException.class, () -> ConsultationScheduler.validate(policy("invented")));
        assertThrows(IllegalArgumentException.class, () -> ConsultationScheduler.validate(policy("opponent_spell", "opponent_spell")));
        JsonObject extra = policy(); extra.addProperty("disableMandatoryReviews", true);
        assertThrows(IllegalArgumentException.class, () -> ConsultationScheduler.validate(extra));
    }
}
