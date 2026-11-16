package forge.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;

/** Validated, non-secret desktop preferences. */
record DesktopAdvisorSettings(String model, String effort, int timeout) {
    private static final Set<String> EFFORTS = Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    static final DesktopAdvisorSettings DEFAULT = new DesktopAdvisorSettings("gpt-6-luna", "high", 60);

    DesktopAdvisorSettings {
        if (model == null || model.isBlank() || effort == null || !EFFORTS.contains(effort) || timeout < 1 || timeout > 300) {
            throw new IllegalArgumentException("Choose a model, reasoning effort, and timeout between 1 and 300 seconds.");
        }
        model = model.trim();
    }

    static DesktopAdvisorSettings parse(String json) {
        JsonObject saved = JsonParser.parseString(json).getAsJsonObject();
        return new DesktopAdvisorSettings(saved.get("model").getAsString(), saved.get("effort").getAsString(),
                saved.get("timeout").getAsBigDecimal().intValueExact());
    }

    String json() {
        JsonObject saved = new JsonObject();
        saved.addProperty("model", model);
        saved.addProperty("effort", effort);
        saved.addProperty("timeout", timeout);
        return saved.toString();
    }
}
