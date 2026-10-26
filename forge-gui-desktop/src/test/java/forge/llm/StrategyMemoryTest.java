package forge.llm;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class StrategyMemoryTest {
    private JsonObject input(StrategyMemory memory, String player, int turn) {
        JsonObject input = new JsonObject(); memory.attach(input, player, turn); return input;
    }

    @Test public void rejectedAndUnconfirmedProposalsNeverBecomeContext() {
        StrategyMemory memory = new StrategyMemory();
        memory.propose("ai", "rejected", 1, "Reserve blue mana.");
        assertFalse(memory.complete("rejected", false));
        assertFalse(input(memory, "ai", 1).has("strategyMemory"));
        memory.propose("ai", "unconfirmed", 1, "Keep protection available.");
        assertFalse(input(memory, "ai", 1).has("strategyMemory"));
        assertFalse(memory.complete("unconfirmed", true));
    }

    @Test public void acceptedNotesArePlayerSpecificAndExpire() {
        StrategyMemory memory = new StrategyMemory();
        memory.propose("ai", "accepted", 1, "Keep protection available.");
        assertTrue(memory.complete("accepted", true));
        assertFalse(input(memory, "other-game", 1).has("strategyMemory"));
        JsonObject notes = input(memory, "ai", 5).getAsJsonObject("strategyMemory");
        assertEquals(notes.get("notes").getAsString(), "Keep protection available.");
        assertEquals(notes.get("ageInTurns").getAsInt(), 4);
        assertFalse(input(memory, "ai", 6).has("strategyMemory"));
    }

    @Test public void acceptedEmptyNotesClearMemoryAndExcessiveNotesAreIgnored() {
        StrategyMemory memory = new StrategyMemory();
        memory.propose("ai", "one", 1, "Retain resources."); memory.complete("one", true);
        memory.propose("ai", "two", 1, "x".repeat(StrategyMemory.MAX_CHARACTERS + 1));
        assertFalse(memory.complete("two", true));
        assertTrue(input(memory, "ai", 1).has("strategyMemory"));
        memory.propose("ai", "clear", 1, ""); assertTrue(memory.complete("clear", true));
        assertFalse(input(memory, "ai", 1).has("strategyMemory"));
    }

    @Test public void oldGamesAreEvictedFromMemory() {
        StrategyMemory memory = new StrategyMemory();
        for (int n = 0; n < 100; n++) {
            memory.propose("game-" + n, "decision-" + n, 1, "Conditional intent."); memory.complete("decision-" + n, true);
        }
        assertFalse(input(memory, "game-0", 1).has("strategyMemory"));
        assertTrue(input(memory, "game-99", 1).has("strategyMemory"));
    }
}
