package forge.llm;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.*;

public class ProviderClientTest {
    private static JsonObject snapshot() throws IOException {
        return LlmHttp.object("{\"schemaVersion\":1,\"decisionId\":\"example\",\"decisionType\":\"land_from_hand\","
                + "\"baselineActionId\":\"land-a\",\"candidates\":[{\"actionId\":\"land-a\"},{\"actionId\":\"land-b\"}]}");
    }

    private static String openRouter(String answer, String finish) {
        JsonObject message = new JsonObject();
        message.addProperty("content", answer);
        return "{\"choices\":[{\"finish_reason\":\"" + finish + "\",\"message\":" + message + "}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> request = new AtomicReference<>();
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();

        Server(int status, String body, long delayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (delayMillis > 0) {
                    try { Thread.sleep(delayMillis); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
        }

        ProviderClient client(ProviderClient.Provider provider) {
            return new ProviderClient(provider, URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/"), () -> "test-key-do-not-log");
        }

        @Override public void close() { server.stop(0); }
    }

    @Test
    public void openRouterUsesBearerAndAcceptsOnlyCapturedActions() throws Exception {
        String answer = "{\"actionId\":\"land-b\",\"reason\":\"This land enables the combo.\"}";
        try (Server server = new Server(200, openRouter(answer, "stop"), 0)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                    "chosen/model", snapshot(), Duration.ofSeconds(5));
            assertEquals(report.get("status").getAsString(), "legal_recommendation");
            assertFalse(report.get("agreesWithBaseline").getAsBoolean());
            assertEquals(report.get("recommendedActionId").getAsString(), "land-b");
            assertEquals(report.get("qualityAssessment").getAsString(), "unmeasured");
            assertEquals(server.authorization.get(), "Bearer test-key-do-not-log");
            assertFalse(server.request.get().contains("test-key-do-not-log"));
            assertEquals(server.calls.get(), 1);
        }
    }

    @Test
    public void unknownActionAndMalformedJsonKeepBaseline() throws Exception {
        for (String answer : new String[] {"{\"actionId\":\"invented-action\",\"reason\":\"Win.\"}",
                "not JSON", "{\"actionId\":\"land-b\",\"reason\":\"ok\"} trailing", "{\"actionId\":42,\"reason\":\"ok\"}"}) {
            try (Server server = new Server(200, openRouter(answer, "stop"), 0)) {
                JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                        "test/model", snapshot(), Duration.ofSeconds(5));
                assertEquals(report.get("status").getAsString(), "invalid_response");
                assertEquals(report.get("recommendedActionId").getAsString(), "land-a");
            }
        }
    }

    @Test
    public void truncatedCompletionIsNotAccepted() throws Exception {
        try (Server server = new Server(200, openRouter("{\"actionId\":\"land-b\",\"reason\":\"ok\"}", "length"), 0)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                    "test/model", snapshot(), Duration.ofSeconds(5));
            assertEquals(report.get("status").getAsString(), "provider_error");
        }
    }

    @Test public void optionalStrategyNotesNeverInvalidateALegalMoveWhenMalformed() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            JsonObject answer = new JsonObject(); answer.addProperty("actionId", "land-b"); answer.addProperty("reason", "Reserve resources.");
            if (variant == 0) { answer.addProperty("strategyNotes", "Keep protection available if the opponent threatens the combo."); }
            if (variant == 1) { answer.addProperty("strategyNotes", "x".repeat(StrategyMemory.MAX_CHARACTERS + 1)); }
            if (variant == 2) { answer.addProperty("strategyNotes", 42); }
            JsonObject input = snapshot(); input.addProperty("strategyNotesRequested", true);
            try (Server server = new Server(200, openRouter(answer.toString(), "stop"), 0)) {
                JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER), "model", input, Duration.ofSeconds(5));
                assertEquals(report.get("status").getAsString(), "legal_recommendation");
                assertEquals(report.has("strategyNotes"), variant == 0);
            }
        }
    }

    @Test
    public void streamMustReachCompletedAndErrorsDoNotExposeSecrets() throws Exception {
        String delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"{}\"}\n\n";
        assertThrows(IOException.class, () -> ProviderClient.parseResponses(delta));
        assertThrows(IOException.class, () -> ProviderClient.parseResponses(delta
                + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"secret\"}}}\n\n"));
        assertThrows(IOException.class, () -> ProviderClient.parseResponses(delta
                + "data: {\"type\":\"response.incomplete\"}\n\n"));
        ProviderClient.Completion completion = ProviderClient.parseResponses(delta
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":10}}}\n\n");
        assertEquals(completion.text(), "{}");
        assertEquals(completion.usage().get("input_tokens").getAsInt(), 10);
        assertEquals(ProviderClient.parseResponses(delta
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")
                .text(), "{}", "an empty terminal output array must not erase completed text deltas");
    }

    @Test
    public void chatGptSendsDocumentedPlanRequestAndUsesFinalOutput() throws Exception {
        String answer = "{\"actionId\":\"land-b\",\"reason\":\"It enables Stage.\"}";
        JsonObject part = new JsonObject();
        part.addProperty("type", "output_text");
        part.addProperty("text", answer);
        String events = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"content\":[" + part + "]}]}}\n\n";
        try (Server server = new Server(200, events, 0)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.CHATGPT),
                    "account-model", snapshot(), Duration.ofSeconds(5), "high", 8192);
            assertEquals(report.get("status").getAsString(), "legal_recommendation");
            JsonObject request = LlmHttp.object(server.request.get());
            assertFalse(request.get("store").getAsBoolean());
            assertTrue(request.get("stream").getAsBoolean());
            assertEquals(request.get("model").getAsString(), "account-model");
            assertEquals(request.getAsJsonObject("reasoning").get("effort").getAsString(), "high");
            assertFalse(request.has("max_output_tokens"), "the ChatGPT plan route rejects this API-key-only option");
            assertTrue(request.get("input").isJsonArray(), "plan usage requires input messages, not a bare string");
            assertEquals(request.getAsJsonArray("input").get(0).getAsJsonObject().get("role").getAsString(), "user");
            assertEquals(report.get("reasoningEffort").getAsString(), "high");
            assertEquals(report.get("outputLimitPolicy").getAsString(), "provider_managed");
            assertFalse(report.has("maxOutputTokens"));
            assertFalse(request.has("tools"));
        }
    }

    @Test
    public void providerErrorsAreRedactedAndNotRetried() throws Exception {
        try (Server server = new Server(401, "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"test-key-do-not-log\"}}", 0)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                    "test/model", snapshot(), Duration.ofSeconds(5));
            assertEquals(report.get("status").getAsString(), "provider_error");
            assertTrue(report.get("error").getAsString().contains("invalid_api_key"));
            assertFalse(report.toString().contains("test-key-do-not-log"));
            assertEquals(server.calls.get(), 1);
        }
    }

    @Test
    public void invalidParameterDiagnosticsHandleNullCodesAndRedactCredentials() throws Exception {
        try (Server server = new Server(400, "{\"error\":{\"code\":null,\"param\":\"input\","
                + "\"type\":\"invalid_request_error\",\"message\":\"Invalid input test-key-do-not-log\"}}", 0)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.CHATGPT),
                    "test/model", snapshot(), Duration.ofSeconds(5));
            assertTrue(report.get("error").getAsString().contains("param=input"));
            assertTrue(report.get("error").getAsString().contains("[redacted]"));
            assertFalse(report.toString().contains("test-key-do-not-log"));
        }
    }

    @Test
    public void totalTimeoutKeepsBaselineWithoutRetry() throws Exception {
        try (Server server = new Server(200, "{}", 300)) {
            JsonObject report = ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                    "test/model", snapshot(), Duration.ofMillis(100));
            assertEquals(report.get("status").getAsString(), "provider_error");
            assertEquals(report.get("recommendedActionId").getAsString(), "land-a");
            assertTrue(report.get("elapsedMillis").getAsLong() < 1500);
            assertTrue(server.calls.get() <= 1);
        }
    }

    @Test
    public void invalidSnapshotIsRejectedBeforeAnyProviderRequest() throws Exception {
        JsonObject snapshot = snapshot();
        snapshot.addProperty("baselineActionId", "missing");
        try (Server server = new Server(200, "{}", 0)) {
            assertThrows(IOException.class, () -> ShadowReview.review(server.client(ProviderClient.Provider.OPENROUTER),
                    "test/model", snapshot, Duration.ofSeconds(5)));
            assertEquals(server.calls.get(), 0);
        }
    }

    @Test
    public void modelCatalogUsesAccountSpecificSlugs() throws Exception {
        try (Server server = new Server(200, "{\"models\":[{\"slug\":\"available\",\"display_name\":\"Available\",\"visibility\":\"list\"},"
                + "{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}", 0)) {
            var models = server.client(ProviderClient.Provider.CHATGPT).models();
            assertEquals(models.size(), 1);
            assertEquals(models.get(0).getAsJsonObject().get("id").getAsString(), "available");
        }
    }
}
