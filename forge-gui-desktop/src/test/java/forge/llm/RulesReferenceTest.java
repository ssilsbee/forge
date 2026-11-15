package forge.llm;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class RulesReferenceTest {
    private final RulesReference reference = RulesFixtures.reference();
    private JsonObject lookup(String tool, String field, String value) {
        JsonObject args = new JsonObject(); args.addProperty(field, value);
        return reference.lookup(tool, args);
    }

    @Test public void localRulesFindKeywordsAndIncludeExamplesAndSubrules() {
        assertNull(RulesReference.class.getResourceAsStream("ComprehensiveRules.txt"), "No rulebook may be bundled in the app");
        JsonObject flying = lookup("search_rules", "query", "flying");
        assertTrue(flying.toString().contains("702.9"));
        JsonObject section = lookup("read_rules", "rule_number", "702.9");
        assertTrue(section.toString().contains("702.9a"));
        assertTrue(section.toString().contains("702.9b"));
        assertFalse(section.get("truncated").getAsBoolean());
        assertTrue(lookup("read_rules", "rule_number", "302.6").toString().contains("summoning sickness"));
        assertTrue(lookup("read_rules", "rule_number", "101.2").toString().contains("Example:"));
        assertEquals(section.get("edition").getAsString(), "2026-11-06");
    }

    @Test public void lookupsAreBoundedReadOnlyAndCachedWithoutSharingMutableResults() {
        JsonObject large = lookup("read_rules", "rule_number", "702");
        assertTrue(large.get("truncated").getAsBoolean());
        int chars = 0;
        for (var entry : large.getAsJsonArray("excerpts")) { chars += entry.getAsJsonObject().get("text").getAsString().length(); }
        assertTrue(chars <= RulesReference.MAX_RESULT_CHARACTERS);
        assertTrue(lookup("read_rules", "rule_number", "../../passwords").has("error"));
        assertTrue(lookup("shell", "rule_number", "ls").has("error"));
        assertTrue(lookup("search_rules", "query", "x".repeat(201)).has("error"));
        assertEquals(lookup("search_rules", "query", "no-such-mechanic-xyz").getAsJsonArray("excerpts").size(), 0);
        lookup("read_rules", "rule_number", "702.9").getAsJsonArray("excerpts").remove(0);
        assertTrue(lookup("read_rules", "rule_number", "702.9").getAsJsonArray("excerpts").size() > 1);
    }
}
