package forge.llm;

import com.google.gson.JsonObject;
import org.testng.SkipException;
import org.testng.annotations.Test;
import java.nio.file.Path;
import java.time.Duration;
import static org.testng.Assert.*;

/** One opt-in provider round trip; no hidden game information or credential output. */
public class LiveRulesToolsTest {
    @Test public void chatGptSubscriptionCanReadDownloadedRules() throws Exception {
        if (!Boolean.getBoolean("forge.llm.liveRulesEvaluation")) { throw new SkipException("Live-account opt-in required"); }
        Path data = Path.of(System.getProperty("forge.llm.dataDir", "../.forge-llm"));
        RulesRepository rules = RulesRepository.start(data.resolve("rules"));
        rules.refresh();
        assertNotNull(rules.current(), rules.status());
        ProviderClient client = ProviderClient.chatGpt(new ChatGptAuth(data.resolve("connections")), null).withRules(rules);
        JsonObject question = new JsonObject(); question.addProperty("question", "Can a creature attack on the turn it entered under my control without haste?");
        ProviderClient.Completion response = client.completeWithInstructions(
                "This is a rules-tool integration check. Before answering, call read_rules with rule_number 302.6. "
                        + "After reading the result, return only JSON with a boolean canAttack and string rule_number. Do not answer from memory.",
                System.getProperty("forge.llm.model", "gpt-6-luna"), "high", 8192, question, Duration.ofSeconds(90));
        assertFalse(response.rulesLookups().isEmpty(), "The subscription route must execute a real local lookup");
        assertTrue(response.rulesLookups().toString().contains("302.6"));
        assertFalse(LlmHttp.object(response.text()).get("canAttack").getAsBoolean());
        System.out.println("Live rules reference: " + response.rulesLookups().size() + " lookup(s); model used rule 302.6 and correctly declined the attack.");
    }
}
