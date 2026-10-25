package forge.llm;

import forge.ai.llm.LiveDecisionBridge;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Explicit desktop entry point using the shared Android/desktop advisor. */
public final class LivePlayLauncher extends LiveAdvisor {
    private static LivePlayLauncher active;
    LivePlayLauncher(ProviderClient provider, String model, String effort, Duration timeout, Path directory) {
        super(provider, model, effort, timeout, directory);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(System.getProperty("forge.llm.dataDir"));
        String connection = System.getProperty("forge.llm.provider", "chatgpt");
        ProviderClient provider;
        if (connection.equals("chatgpt")) {
            provider = ProviderClient.chatGpt(new ChatGptAuth(directory.resolve("connections")), System.getProperty("forge.llm.profile"));
        } else if (connection.equals("openrouter")) { provider = ProviderClient.openRouter(System.getenv("OPENROUTER_API_KEY")); }
        else { throw new IOException("Unsupported live provider."); }
        int seconds = Integer.getInteger("forge.llm.timeoutSeconds", 45);
        if (seconds < 1 || seconds > 300) { throw new IOException("Timeout must be between 1 and 300 seconds."); }
        String model = System.getProperty("forge.llm.model", "gpt-6-luna");
        String effort = System.getProperty("forge.llm.reasoningEffort", "high");
        active = new LivePlayLauncher(provider, model, effort, Duration.ofSeconds(seconds), directory);
        SwingUtilities.invokeAndWait(() -> active.setMonitor(new LlmMonitor(model, effort, active::pause)));
        LiveDecisionBridge.install(active);
        forge.view.Main.main(new String[0]);
    }

    static void resume() {
        if (active != null) {
            active.resumeLive();
        }
    }


}
