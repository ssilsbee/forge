package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;
import static org.testng.Assert.*;

public class RulesToolsTest {
    private static final String ANSWER = "{\"actionId\":\"pass\",\"reason\":\"Wait for the artifact to resolve before destroying it.\"}";

    private static JsonObject call(boolean router, String name, String arguments) {
        JsonObject function = new JsonObject(); function.addProperty("name", name); function.addProperty("arguments", arguments);
        if (!router) { function.addProperty("type", "function_call"); function.addProperty("call_id", "lookup-1"); return function; }
        JsonObject call = new JsonObject(); call.addProperty("id", "lookup-1"); call.addProperty("type", "function");
        call.add("function", function); return call;
    }

    private static String reply(boolean router, JsonObject call) {
        if (router) {
            JsonObject assistant = new JsonObject(); assistant.addProperty("role", "assistant");
            if (call != null) {
                JsonArray calls = new JsonArray(); calls.add(call); assistant.add("tool_calls", calls);
                assistant.add("reasoning_details", com.google.gson.JsonParser.parseString("[{\"type\":\"reasoning.encrypted\",\"data\":\"opaque\"}]"));
            } else { assistant.addProperty("content", ANSWER); assistant.add("tool_calls", com.google.gson.JsonNull.INSTANCE); }
            return "{\"choices\":[{\"finish_reason\":\"" + (call == null ? "stop" : "tool_calls") + "\",\"message\":" + assistant
                    + "}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"cost\":0.0125}}";
        }
        JsonArray output = new JsonArray();
        if (call != null) {
            output.add(com.google.gson.JsonParser.parseString("{\"type\":\"reasoning\",\"id\":\"r1\",\"summary\":[],\"encrypted_content\":\"opaque\"}"));
            output.add(call);
        } else {
            JsonObject part = new JsonObject(); part.addProperty("type", "output_text"); part.addProperty("text", ANSWER);
            JsonArray content = new JsonArray(); content.add(part);
            JsonObject message = new JsonObject(); message.addProperty("type", "message"); message.add("content", content); output.add(message);
        }
        return "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":" + output
                + ",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}}\n\n";
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final List<JsonObject> requests = new CopyOnWriteArrayList<>();
        Server(IntFunction<String> replies) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.add(LlmHttp.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = replies.apply(requests.size()).getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start();
        }
        ProviderClient client(boolean router) {
            return new ProviderClient(router ? ProviderClient.Provider.OPENROUTER : ProviderClient.Provider.CHATGPT,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "mock-key")
                    .withRulesReference(RulesFixtures.reference());
        }
        @Override public void close() { server.stop(0); }
    }

    private JsonObject snapshot() throws IOException {
        return LlmHttp.object("{\"schemaVersion\":1,\"decisionId\":\"artifact\",\"decisionType\":\"live_action\","
                + "\"baselineActionId\":\"cast\",\"candidates\":[{\"actionId\":\"pass\"},{\"actionId\":\"cast\"}]}");
    }

    @Test public void bothProvidersContinueAfterSearchAndReadAndRetainUsageAndTrace() throws Exception {
        for (boolean router : new boolean[]{true, false}) {
            try (Server server = new Server(n -> reply(router, n == 1 ? call(router, "search_rules", "{\"query\":\"permanent\"}")
                    : n == 2 ? call(router, "read_rules", "{\"rule_number\":\"110.1\"}") : null))) {
                List<JsonObject> observed = new ArrayList<>();
                JsonObject report = ShadowReview.review(server.client(router), "mock", snapshot(), Duration.ofSeconds(5), "high", 8192, observed::add);
                assertEquals(report.get("status").getAsString(), "legal_recommendation");
                assertEquals(report.get("recommendedActionId").getAsString(), "pass");
                assertEquals(observed.size(), 2);
                assertEquals(report.getAsJsonArray("rulesLookups").size(), 2);
                assertTrue(report.getAsJsonArray("rulesLookups").get(1).toString().contains("battlefield"));
                assertFalse(report.toString().contains("opaque"), "Do not log provider reasoning items");
                assertEquals(report.getAsJsonObject("usage").get(router ? "prompt_tokens" : "input_tokens").getAsInt(), 30);
                if (router) { assertEquals(report.getAsJsonObject("usage").get("cost").getAsDouble(), 0.0375, 0.00000001); }
                assertEquals(server.requests.size(), 3);
                JsonObject continuation = server.requests.get(1);
                JsonArray conversation = continuation.getAsJsonArray(router ? "messages" : "input");
                assertTrue(conversation.toString().contains("lookup-1"));
                assertTrue(conversation.toString().contains("opaque"), "Preserve reasoning on continuation only");
                assertTrue(conversation.toString().contains(router ? "tool_call_id" : "function_call_output"));
                assertEquals(server.requests.get(2).get("tool_choice").getAsString(), "none");
                assertEquals(continuation.getAsJsonArray("tools").size(), 2);
            }
        }
    }

    @Test public void invalidArgumentsReturnToolErrorAndStillAllowFinalMove() throws Exception {
        for (String arguments : new String[]{"not json", "{\"rule_number\":\"../../secret\"}"}) {
            try (Server server = new Server(n -> reply(true, n == 1 ? call(true, "read_rules", arguments) : null))) {
                JsonObject report = ShadowReview.review(server.client(true), "mock", snapshot(), Duration.ofSeconds(5));
                assertEquals(report.get("status").getAsString(), "legal_recommendation");
                assertTrue(report.getAsJsonArray("rulesLookups").get(0).getAsJsonObject().getAsJsonObject("result").has("error"));
            }
        }
    }

    @Test public void repeatedCallsStopAtBudgetAndRetainDiagnosticsOnFailure() throws Exception {
        for (boolean router : new boolean[]{true, false}) {
            try (Server server = new Server(n -> reply(router, call(router, "search_rules", "{\"query\":\"flying\"}")))) {
                JsonObject report = ShadowReview.review(server.client(router), "mock", snapshot(), Duration.ofSeconds(5));
                assertEquals(report.get("status").getAsString(), "provider_error");
                assertTrue(report.get("error").getAsString().contains("budget"));
                assertEquals(server.requests.size(), 3);
                assertEquals(report.getAsJsonArray("rulesLookups").size(), 2);
            }
        }
    }

    @Test public void lookupDoesNotResetDeadlineAndRecentRulesAreReused() throws Exception {
        try (Server server = new Server(n -> reply(true, n == 1 ? call(true, "read_rules", "{\"rule_number\":\"302.6\"}") : null))) {
            ProviderClient client = server.client(true);
            assertThrows(IOException.class, () -> client.complete("mock", null, 8192, snapshot(), Duration.ofMillis(500), trace -> {
                try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }));
            assertEquals(server.requests.size(), 1, "No fresh timeout for continuation");
            client.complete("mock", snapshot(), Duration.ofSeconds(5));
            assertTrue(server.requests.get(1).getAsJsonArray("messages").toString().contains("302.6"));
        }
    }

    @Test public void chatGptCanContinueFromCompletedItemsWhenTerminalOutputIsEmpty() throws Exception {
        JsonObject toolCall = call(false, "read_rules", "{\"rule_number\":\"302.6\"}");
        String toolEvents = "data: {\"type\":\"response.output_item.done\",\"item\":" + toolCall + "}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
        try (Server server = new Server(n -> n == 1 ? toolEvents : reply(false, null))) {
            var result = server.client(false).complete("mock", snapshot(), Duration.ofSeconds(5));
            assertEquals(result.text(), ANSWER);
            assertEquals(result.rulesLookups().size(), 1);
        }
    }

    @Test public void fourBatchedLookupsAreAllowedButFurtherToolsAreDisabled() throws Exception {
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant");
        JsonArray calls = new JsonArray();
        for (int i = 0; i < 4; i++) {
            JsonObject call = call(true, "read_rules", "{\"rule_number\":\"302.6\"}");
            call.addProperty("id", "batch-" + i); calls.add(call);
        }
        message.add("tool_calls", calls);
        String batched = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":" + message + "}]}";
        try (Server server = new Server(n -> n == 1 ? batched : reply(true, null))) {
            var result = server.client(true).complete("mock", snapshot(), Duration.ofSeconds(5));
            assertEquals(result.rulesLookups().size(), 4);
            assertEquals(server.requests.get(1).get("tool_choice").getAsString(), "none");
        }
    }

    @Test public void explicitDisableRetainsTheOriginalSingleAnswerPath() throws Exception {
        String previous = System.getProperty("forge.llm.rules.disabled");
        System.setProperty("forge.llm.rules.disabled", "true");
        try (Server server = new Server(n -> reply(true, null))) {
            var result = server.client(true).complete("mock", snapshot(), Duration.ofSeconds(5));
            assertEquals(result.text(), ANSWER);
            assertFalse(server.requests.get(0).has("tools"));
            assertTrue(result.rulesLookups().isEmpty());
        } finally {
            if (previous == null) { System.clearProperty("forge.llm.rules.disabled"); }
            else { System.setProperty("forge.llm.rules.disabled", previous); }
        }
    }

    @Test public void failedDownloadWithoutCacheDoesNotAdvertiseToolsToEitherProvider() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("rules-tools-missing");
        var repository = new RulesRepository(directory, uri -> { throw new IOException("offline"); },
                java.time.Clock.systemUTC(), message -> { });
        repository.refresh();
        for (boolean router : new boolean[]{true, false}) {
            try (Server server = new Server(n -> reply(router, null))) {
                var result = server.client(router).withRules(repository).complete("mock", snapshot(), Duration.ofSeconds(5));
                assertEquals(result.text(), ANSWER);
                assertFalse(server.requests.get(0).has("tools"));
                assertFalse(server.requests.get(0).toString().contains("Reference edition:"));
                assertTrue(result.rulesLookups().isEmpty());
                assertEquals(server.requests.size(), 1);
            }
        }
    }
}
