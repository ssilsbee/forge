package forge.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Offline comparison only; a valid candidate ID is not evidence that the model plays better. */
public final class ShadowReview {
    private ShadowReview() { }

    public static JsonObject review(ProviderClient provider, String model, JsonObject snapshot,
                                    Duration timeout) throws IOException {
        return review(provider, model, snapshot, timeout, null, 8192);
    }

    public static JsonObject review(ProviderClient provider, String model, JsonObject snapshot,
                                    Duration timeout, String reasoningEffort, int maxOutputTokens) throws IOException {
        Set<String> candidates = validateSnapshot(snapshot);
        String baseline = snapshot.get("baselineActionId").getAsString();
        JsonObject report = new JsonObject();
        report.addProperty("schemaVersion", 1);
        report.addProperty("decisionId", snapshot.get("decisionId").getAsString());
        report.addProperty("reviewedAt", Instant.now().toString());
        report.addProperty("provider", provider.name());
        report.addProperty("model", model);
        report.addProperty("reasoningEffort", reasoningEffort == null ? "provider_default" : reasoningEffort);
        if (provider.supportsOutputLimit()) {
            report.addProperty("maxOutputTokens", maxOutputTokens);
        }
        report.addProperty("outputLimitPolicy", provider.supportsOutputLimit() ? "client_token_cap" : "provider_managed");
        report.addProperty("mode", "shadow");
        report.addProperty("baselineActionId", baseline);
        report.addProperty("recommendedActionId", baseline);
        report.addProperty("qualityAssessment", "unmeasured");
        long started = System.nanoTime();
        ProviderClient.Completion completion;
        try {
            completion = provider.complete(model, reasoningEffort, maxOutputTokens, snapshot, timeout);
            report.add("usage", completion.usage());
        } catch (IOException e) {
            report.addProperty("status", "provider_error");
            report.addProperty("error", e.getMessage());
            report.addProperty("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            return report;
        }
        report.addProperty("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
        // Keep the bounded visible answer for auditing format failures. Never collect reasoning events.
        report.addProperty("responseText", completion.text().substring(0, Math.min(completion.text().length(), 4096)));
        try {
            JsonObject answer = LlmHttp.object(completion.text());
            if (answer.size() != 2 || !answer.has("actionId") || !answer.has("reason")
                    || !answer.get("actionId").isJsonPrimitive() || !answer.get("actionId").getAsJsonPrimitive().isString()
                    || !answer.get("reason").isJsonPrimitive() || !answer.get("reason").getAsJsonPrimitive().isString()) {
                throw new IOException("Expected actionId and reason strings.");
            }
            String selected = answer.get("actionId").getAsString();
            String reason = answer.get("reason").getAsString();
            if (!candidates.contains(selected) || reason.isBlank() || reason.length() > 1000) {
                throw new IOException("Invalid candidate or explanation.");
            }
            report.addProperty("status", "legal_recommendation");
            report.addProperty("recommendedActionId", selected);
            report.addProperty("agreesWithBaseline", baseline.equals(selected));
            report.addProperty("reason", reason);
        } catch (IOException | RuntimeException e) {
            report.addProperty("status", "invalid_response");
            report.addProperty("error", "The model did not return a valid captured action and explanation.");
        }
        return report;
    }

    static Set<String> validateSnapshot(JsonObject snapshot) throws IOException {
        try {
            if (snapshot.get("schemaVersion").getAsInt() != 1
                    || !Set.of("land_from_hand", "benchmark_action", "sequence_action", "live_action").contains(snapshot.get("decisionType").getAsString())
                    || snapshot.get("decisionId").getAsString().isBlank()) {
                throw new IOException("Unsupported decision snapshot.");
            }
            Set<String> ids = new HashSet<>();
            for (JsonElement candidate : snapshot.getAsJsonArray("candidates")) {
                String id = candidate.getAsJsonObject().get("actionId").getAsString();
                if (id.isBlank() || !ids.add(id)) { throw new IOException("Duplicate or empty candidate."); }
            }
            if (ids.size() < 2 || !ids.contains(snapshot.get("baselineActionId").getAsString())) {
                throw new IOException("Snapshot lacks a valid baseline and alternatives.");
            }
            return ids;
        } catch (RuntimeException e) {
            throw new IOException("Invalid decision snapshot.");
        }
    }
}
