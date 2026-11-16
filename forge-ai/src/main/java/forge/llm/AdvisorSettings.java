package forge.llm;

import java.time.Duration;
import java.util.List;

/** Validated runtime configuration shared by native UIs. Persistence stays platform-owned. */
public record AdvisorSettings(ProviderClient.Provider provider, String model, String effort,
                              int timeoutSeconds, String openRouterKey) {
    public static final String DEFAULT_MODEL = "gpt-6-luna";
    public static final List<String> EFFORTS = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    public AdvisorSettings {
        if (provider == null || model == null || model.isBlank() || effort == null
                || !EFFORTS.contains(effort) || timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Choose a model, reasoning effort, and timeout between 1 and 300 seconds.");
        }
        model = model.trim();
        openRouterKey = provider == ProviderClient.Provider.CHATGPT || openRouterKey == null ? null : openRouterKey.trim();
    }
    public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
    // Records otherwise include every field, including the API key, in diagnostic messages.
    @Override public String toString() { return provider + " / " + model + " / " + effort + " / " + timeoutSeconds + "s"; }
}
