package forge.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Validated, non-secret desktop preferences. */
record DesktopAdvisorSettings(String model, String effort, int timeout) {
    static final DesktopAdvisorSettings DEFAULT = new DesktopAdvisorSettings("gpt-6-luna", "high", 60);

    DesktopAdvisorSettings {
        model = new AdvisorSettings(ProviderClient.Provider.CHATGPT, model, effort, timeout, null).model();
    }

    AdvisorSettings runtime() { return new AdvisorSettings(ProviderClient.Provider.CHATGPT, model, effort, timeout, null); }

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
