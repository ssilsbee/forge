package forge.llm;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Worker-owned, bounded intent notes. Proposals are committed only after engine acceptance. */
final class StrategyMemory {
    static final int MAX_CHARACTERS = 2000;
    private static final int MAX_AGE = 4;
    private record Note(String player, String decision, int turn, String text) { }
    private final Map<String, Note> committed = new LinkedHashMap<>();
    private final Map<String, Note> proposed = new LinkedHashMap<>();

    void propose(String player, String decision, int turn, String text) {
        if (text.length() > MAX_CHARACTERS) { return; }
        proposed.put(decision, new Note(player, decision, turn, text));
        trim(proposed, 64);
    }

    boolean complete(String decision, boolean accepted) {
        Note note = proposed.remove(decision);
        if (note == null || !accepted) { return false; }
        if (note.text().isBlank()) { committed.remove(note.player()); }
        else {
            committed.remove(note.player());
            committed.put(note.player(), note);
            trim(committed, 16);
        }
        return true;
    }

    void attach(JsonObject input, String player, int turn) {
        // A previous selection that never reached an acceptance callback must not become memory.
        proposed.entrySet().removeIf(entry -> entry.getValue().player().equals(player));
        input.addProperty("strategyNotesRequested", true);
        Note note = committed.get(player);
        if (note == null) { return; }
        int age = turn - note.turn();
        if (age < 0 || age > MAX_AGE) { committed.remove(player); return; }
        JsonObject memory = new JsonObject();
        memory.addProperty("notes", note.text());
        memory.addProperty("fromDecisionId", note.decision());
        memory.addProperty("writtenOnTurn", note.turn());
        memory.addProperty("ageInTurns", age);
        memory.addProperty("authority", "Conditional intent from an accepted choice, not proof a spell resolved or a future action occurred. Current state and legal actions override these notes.");
        input.add("strategyMemory", memory);
    }

    private static void trim(Map<String, Note> map, int maximum) {
        while (map.size() > maximum) { map.remove(map.keySet().iterator().next()); }
    }
}
