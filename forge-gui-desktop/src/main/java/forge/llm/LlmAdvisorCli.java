package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import forge.ai.llm.PrivateFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Separate process: provider credentials, auth and model latency never enter live game decisions. */
public final class LlmAdvisorCli {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private LlmAdvisorCli() { }

    public static void main(String[] args) {
        try {
            int status = run(args);
            if (status != 0) { System.exit(status); }
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            // Do not send model prompts, auth objects or token-bearing exceptions to Sentry/loggers.
            System.err.println("Invalid advisor configuration or provider response.");
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException {
        if (args.length == 0 || "help".equals(args[0])) {
            System.out.println("Forge LLM Advisor: sample, login, profiles, models, review, logout, benchmark-export, benchmark-run, deck-plan, sequence-run\n"
                    + "Options: --data-dir DIR --profile ID --new-account --provider chatgpt|openrouter\n"
                    + "         --model MODEL --reasoning-effort high --max-output-tokens 8192\n"
                    + "         --input SNAPSHOT --output FILE --timeout-seconds 60\n"
                    + "Review is shadow mode. ChatGPT uses your authorized plan; OpenRouter uses OPENROUTER_API_KEY.");
            return 0;
        }
        Map<String, String> options = parseOptions(args);
        Path defaultDirectory = Path.of(System.getProperty("user.home"), ".forge-llm");
        ChatGptAuth auth = new ChatGptAuth(Path.of(options.getOrDefault("data-dir", defaultDirectory.toString())));
        String profile = options.get("profile");
        switch (args[0]) {
            case "login":
                String id = auth.login(profile, options.containsKey("new-account"));
                System.out.println("Connected using ChatGPT plan. Profile: " + id);
                System.out.println("Manage usage: https://chatgpt.com/settings/usage");
                return 0;
            case "profiles":
                System.out.println(PRETTY.toJson(auth.profiles()));
                return 0;
            case "logout":
                if (auth.logout(profile)) {
                    System.out.println("Signed out; renewable session revoked. Registration kept for reconnecting.");
                } else {
                    System.out.println("Signed out locally. Remote revocation was not confirmed; disconnect Forge in ChatGPT Settings.");
                }
                return 0;
            case "sample":
                PrivateFiles.write(Path.of(required(options, "output")), PRETTY.toJson(LandDecisionExample.create()));
                System.out.println("Saved the engine-generated Thespian's Stage / Dark Depths decision. No model request was made.");
                return 0;
            case "models":
                System.out.println(PRETTY.toJson(provider(options, auth).models()));
                return 0;
            case "deck-plan":
            case "sequence-run":
                ProviderClient sequenceProvider = provider(options, auth);
                if (!sequenceProvider.supportsOutputLimit() && options.containsKey("max-output-tokens")) {
                    throw new IOException("ChatGPT plan usage does not support a client output-token cap.");
                }
                int sequenceSeconds;
                int sequenceTokens;
                try {
                    sequenceSeconds = Integer.parseInt(options.getOrDefault("timeout-seconds", "60"));
                    sequenceTokens = Integer.parseInt(options.getOrDefault("max-output-tokens", "8192"));
                }
                catch (NumberFormatException e) { throw new IOException("Timeout must be an integer."); }
                if (sequenceSeconds < 1 || sequenceSeconds > 300) { throw new IOException("Timeout must be between 1 and 300 seconds."); }
                Path sequenceOutput = Path.of(required(options, "output"));
                if (args[0].equals("deck-plan")) {
                    JsonObject plan = DeckStrategyPlanner.plan(TurnSequenceExperiment.deck(),
                            sequenceOutput.toAbsolutePath().getParent().resolve("deck-plans"), sequenceProvider,
                            required(options, "model"), options.get("reasoning-effort"), Duration.ofSeconds(sequenceSeconds), sequenceTokens);
                    PrivateFiles.write(sequenceOutput, PRETTY.toJson(plan));
                    System.out.println("Saved the compact deck strategy. It contains no library order.");
                } else {
                    JsonObject sequenceSummary = TurnSequenceExperiment.run(sequenceOutput, sequenceProvider,
                            required(options, "model"), options.get("reasoning-effort"), Duration.ofSeconds(sequenceSeconds), sequenceTokens);
                    System.out.println("Completed " + sequenceSummary.getAsJsonArray("results").size() + " sequence arms. See summary.json.");
                }
                return 0;
            case "benchmark-export":
                JsonObject suite = DecisionBenchmark.export(Path.of(required(options, "output")));
                System.out.println("Verified " + suite.getAsJsonArray("cases").size() + " benchmark positions. No inference requests.");
                return 0;
            case "benchmark-run":
                ProviderClient benchmarkProvider = provider(options, auth);
                if (!benchmarkProvider.supportsOutputLimit() && options.containsKey("max-output-tokens")) {
                    throw new IOException("ChatGPT plan usage does not support a client output-token cap.");
                }
                int benchmarkSeconds;
                int benchmarkTokens;
                try {
                    benchmarkSeconds = Integer.parseInt(options.getOrDefault("timeout-seconds", "60"));
                    benchmarkTokens = Integer.parseInt(options.getOrDefault("max-output-tokens", "8192"));
                } catch (NumberFormatException e) { throw new IOException("Invalid benchmark numeric setting."); }
                if (benchmarkSeconds < 1 || benchmarkSeconds > 300) { throw new IOException("Timeout must be between 1 and 300 seconds."); }
                JsonObject summary = DecisionBenchmark.run(Path.of(required(options, "input")),
                        Path.of(required(options, "output")), benchmarkProvider, required(options, "model"),
                        options.get("reasoning-effort"), Duration.ofSeconds(benchmarkSeconds), benchmarkTokens);
                System.out.println("Completed " + summary.get("attempted") + "/" + summary.get("caseCount") + " reviews; valid: "
                        + summary.get("validRecommendations") + ". See summary.md and summary.json.");
                return summary.get("validRecommendations").getAsInt() == summary.get("caseCount").getAsInt() ? 0 : 2;
            case "review":
                Path input = Path.of(required(options, "input")).toAbsolutePath().normalize();
                Path output = Path.of(required(options, "output")).toAbsolutePath().normalize();
                if (input.equals(output)) { throw new IOException("Keep the review report separate from the input snapshot."); }
                if (Files.size(input) > 512_000) { throw new IOException("Decision snapshot exceeds the prototype size limit."); }
                JsonObject snapshot = LlmHttp.object(Files.readString(input));
                int seconds;
                try { seconds = Integer.parseInt(options.getOrDefault("timeout-seconds", "60")); }
                catch (NumberFormatException e) { throw new IOException("Timeout must be a number of seconds."); }
                if (seconds < 1 || seconds > 300) { throw new IOException("Timeout must be between 1 and 300 seconds."); }
                ProviderClient provider = provider(options, auth);
                if (!provider.supportsOutputLimit() && options.containsKey("max-output-tokens")) {
                    throw new IOException("ChatGPT plan usage does not support a client output-token cap. Use the request deadline and ChatGPT usage controls.");
                }
                int tokenLimit;
                try { tokenLimit = Integer.parseInt(options.getOrDefault("max-output-tokens", "8192")); }
                catch (NumberFormatException e) { throw new IOException("Output token limit must be an integer."); }
                JsonObject report = ShadowReview.review(provider, required(options, "model"), snapshot,
                        Duration.ofSeconds(seconds), options.get("reasoning-effort"), tokenLimit);
                PrivateFiles.write(output, PRETTY.toJson(report));
                System.out.println(PRETTY.toJson(report));
                return "legal_recommendation".equals(report.get("status").getAsString()) ? 0 : 2;
            default:
                throw new IOException("Unknown command. Run help for available advisor commands.");
        }
    }

    private static ProviderClient provider(Map<String, String> options, ChatGptAuth auth) throws IOException {
        switch (required(options, "provider")) {
            case "chatgpt": return ProviderClient.chatGpt(auth, options.get("profile"));
            case "openrouter": return ProviderClient.openRouter(System.getenv("OPENROUTER_API_KEY"));
            default: throw new IOException("Provider must be chatgpt or openrouter.");
        }
    }

    private static String required(Map<String, String> options, String key) throws IOException {
        String value = options.get(key);
        if (value == null || value.isBlank()) { throw new IOException("Missing --" + key + "."); }
        return value;
    }

    private static Map<String, String> parseOptions(String[] args) throws IOException {
        Set<String> allowed = Set.of("data-dir", "profile", "new-account", "provider", "model", "input", "output",
                "timeout-seconds", "reasoning-effort", "max-output-tokens");
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--") || !allowed.contains(args[i].substring(2))) {
                throw new IOException("Unknown advisor option.");
            }
            String key = args[i].substring(2);
            String value;
            if ("new-account".equals(key)) { value = "true"; }
            else if (++i < args.length && !args[i].startsWith("--")) { value = args[i]; }
            else { throw new IOException("Missing value for --" + key + "."); }
            if (options.putIfAbsent(key, value) != null) { throw new IOException("Duplicate --" + key + "."); }
        }
        if (options.containsKey("profile") && options.containsKey("new-account")) {
            throw new IOException("Choose a saved profile or a new account, not both.");
        }
        return options;
    }
}
