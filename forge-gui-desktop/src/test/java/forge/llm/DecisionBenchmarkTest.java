package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

@Test(singleThreaded = true)
public class DecisionBenchmarkTest {
    private Path directory;
    private JsonObject manifest;

    @BeforeClass
    public void fixtures() throws IOException {
        directory = Files.createTempDirectory(Path.of("target"), "decision-benchmark-");
        manifest = DecisionBenchmark.export(directory);
    }

    public void allTwelveFixturesHaveExecutableAndDistinguishingEngineOutcomes() throws IOException {
        assertEquals(manifest.getAsJsonArray("cases").size(), 12);
        for (JsonElement element : manifest.getAsJsonArray("cases")) {
            JsonObject item = element.getAsJsonObject();
            int min = 1, max = 0;
            for (JsonElement result : item.getAsJsonArray("engineOutcomes")) {
                JsonObject outcome = result.getAsJsonObject();
                assertTrue(outcome.get("engineExecuted").getAsBoolean(), item.get("id").toString());
                min = Math.min(min, outcome.get("score").getAsInt());
                max = Math.max(max, outcome.get("score").getAsInt());
            }
            assertEquals(min, 0);
            assertEquals(max, 1);
            JsonObject snapshot = LlmHttp.object(Files.readString(directory.resolve(item.get("inputFile").getAsString())));
            assertFalse(snapshot.has("objective"));
            assertFalse(snapshot.has("engineOutcomes"));
            assertFalse(snapshot.toString().contains("Opponent"), "personal opponent names should not be serialized");
            assertFalse(snapshot.toString().contains("Forest"), "hidden library identity must stay out of questions");
            assertEquals(ShadowReview.validateSnapshot(snapshot).size(), item.getAsJsonArray("engineOutcomes").size());
        }
    }

    public void suiteAndBaselineAreDeterministicAcrossRebuilds() throws IOException {
        Path second = Files.createTempDirectory(Path.of("target"), "decision-benchmark-repeat-");
        JsonObject repeated = DecisionBenchmark.export(second);
        assertEquals(repeated, manifest);
    }

    public void failuresAreNotCountedAsModelSuccesses() throws IOException {
        JsonArray reports = new JsonArray();
        JsonObject failed = new JsonObject();
        failed.addProperty("status", "provider_error");
        failed.addProperty("elapsedMillis", 20);
        reports.add(failed);
        JsonObject summary = DecisionBenchmark.summarize(manifest, reports);
        assertEquals(summary.get("validRecommendations").getAsInt(), 0);
        assertEquals(summary.get("modelObjectivePasses").getAsInt(), 0);
        assertEquals(summary.get("attempted").getAsInt(), 1);
        assertEquals(summary.getAsJsonArray("rows").get(1).getAsJsonObject().get("status").getAsString(), "not_run");
    }

    public void cachedRunDoesNotSpendAgainAndChangedInputsAreRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            JsonObject message = new JsonObject();
            message.addProperty("content", "{\"actionId\":\"pass\",\"reason\":\"Keep the card.\"}");
            byte[] bytes = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":" + message + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ProviderClient client = new ProviderClient(ProviderClient.Provider.OPENROUTER,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "test-key");
            Path output = Files.createTempDirectory(Path.of("target"), "benchmark-reviews-");
            JsonObject first = DecisionBenchmark.run(directory.resolve("manifest.json"), output, client, "test-model", "high", Duration.ofSeconds(5), 8192);
            assertEquals(first.get("validRecommendations").getAsInt(), 12);
            assertEquals(calls.get(), 12);
            assertEquals(DecisionBenchmark.run(directory.resolve("manifest.json"), output, client, "test-model", "high", Duration.ofSeconds(5), 8192), first);
            assertEquals(calls.get(), 12, "cached runs must not repeat inference requests");
            assertThrows(IOException.class, () -> DecisionBenchmark.run(directory.resolve("manifest.json"), output, client, "different-model", "high", Duration.ofSeconds(5), 8192));
            Path copied = Files.createTempDirectory(Path.of("target"), "benchmark-tampered-");
            for (JsonElement element : manifest.getAsJsonArray("cases")) {
                String file = element.getAsJsonObject().get("inputFile").getAsString();
                Files.copy(directory.resolve(file), copied.resolve(file));
            }
            Files.copy(directory.resolve("manifest.json"), copied.resolve("manifest.json"));
            Files.writeString(copied.resolve("case-01.json"), "{}");
            assertThrows(IOException.class, () -> DecisionBenchmark.run(copied.resolve("manifest.json"), output, client, "test-model", "high", Duration.ofSeconds(5), 8192));
            assertEquals(calls.get(), 12);
        } finally { server.stop(0); }
    }
}
