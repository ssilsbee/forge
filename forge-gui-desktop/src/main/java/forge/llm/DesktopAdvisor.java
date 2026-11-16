package forge.llm;

import com.google.gson.JsonArray;
import forge.ai.llm.LiveDecisionBridge;
import forge.ai.llm.PrivateFiles;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Desktop setup and lifecycle for the shared live advisor. */
public final class DesktopAdvisor {
    private static DesktopAdvisor instance;
    private final Path directory = dataDirectory();
    private final ChatGptAuth auth = new ChatGptAuth(directory.resolve("connections"));
    private final JFrame frame = new JFrame("LLM Advisor");
    private final JTextArea status = new JTextArea("Sign in to ChatGPT to get started.", 3, 60);
    private final JComboBox<String> model = new JComboBox<>(new String[]{"gpt-6-luna"});
    private final JComboBox<String> effort = new JComboBox<>(new String[]{"none", "minimal", "low", "medium", "high", "xhigh", "max"});
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(60, 1, 300, 5));
    private final List<JButton> networkButtons = new ArrayList<>();
    private LiveAdvisor advisor;
    private LlmMonitor monitor;
    private RulesRepository rules;
    private DesktopAdvisorSettings activeSettings;
    private int generation;

    private DesktopAdvisor() {
        status.setEditable(false); status.setOpaque(false);
        status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setFont(UIManager.getFont("Label.font"));
        model.setEditable(true);
        effort.setSelectedItem("high");
        DesktopAdvisorSettings saved = DesktopAdvisorSettings.DEFAULT;
        try {
            Path settings = directory.resolve("desktop-settings.json");
            if (Files.exists(settings)) {
                saved = DesktopAdvisorSettings.parse(Files.readString(settings));
            }
        } catch (Exception ignored) { status.setText("Saved settings could not be loaded. Using defaults."); }
        model.setSelectedItem(saved.model());
        effort.setSelectedItem(saved.effort());
        timeout.setValue(saved.timeout());
        JPanel content = new JPanel(new BorderLayout(16, 16));
        content.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        JLabel intro = new JLabel("<html><h2>Play with the LLM Advisor</h2>Connect ChatGPT, choose a model, then enable the advisor.<br>Works with an AI opponent in a two-player Constructed or Commander game.</html>");
        content.add(intro, BorderLayout.NORTH);
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JPanel account = new JPanel(new FlowLayout(FlowLayout.LEFT));
        account.setBorder(BorderFactory.createTitledBorder("ChatGPT connection"));
        account.add(button("Sign in with ChatGPT", true, () -> {
            auth.login(null, false);
            return "Connected to ChatGPT. Choose a model and enable the advisor.";
        }));
        account.add(button("Refresh models", false, () -> {
            JsonArray catalog = provider().models();
            SwingUtilities.invokeLater(() -> {
                Object selected = model.getSelectedItem();
                model.removeAllItems();
                catalog.forEach(item -> model.addItem(item.getAsJsonObject().get("id").getAsString()));
                model.setSelectedItem(selected);
            });
            return "Connection checked. Model list updated.";
        }));
        account.add(button("Sign out", true, () -> {
            boolean revoked = auth.logout(null);
            return revoked ? "Signed out. Forge AI is in control." : "Signed out locally. Remote sign-out was not confirmed; disconnect Forge in ChatGPT Settings.";
        }));
        center.add(account);
        JPanel settings = new JPanel(new GridLayout(3, 2, 12, 12));
        settings.setBorder(BorderFactory.createTitledBorder("Advisor settings"));
        settings.add(new JLabel("Model")); settings.add(model);
        settings.add(new JLabel("Reasoning effort")); settings.add(effort);
        settings.add(new JLabel("Request timeout (seconds)")); settings.add(timeout);
        center.add(settings);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton enable = new JButton("Enable advisor");
        networkButtons.add(enable);
        enable.addActionListener(e -> {
            DesktopAdvisorSettings selected;
            try {
                timeout.commitEdit();
                selected = new DesktopAdvisorSettings(String.valueOf(model.getEditor().getItem()),
                        String.valueOf(effort.getSelectedItem()), (Integer) timeout.getValue());
            } catch (Exception invalid) {
                status.setText("Choose a model and a timeout between 1 and 300 seconds."); return;
            }
            if (advisor != null) { advisor.pause(); }
            int version = ++generation;
            work(() -> {
                ProviderClient client = provider();
                boolean available = false;
                for (var entry : client.models()) {
                    if (selected.model().equals(entry.getAsJsonObject().get("id").getAsString())) { available = true; }
                }
                if (!available) { return "This model is unavailable. Refresh models and choose one from the list."; }
                if (rules == null) { rules = RulesRepository.start(directory.resolve("rules")); }
                client.withRules(rules);
                PrivateFiles.write(directory.resolve("desktop-settings.json"), selected.json());
                boolean[] installed = {false};
                SwingUtilities.invokeAndWait(() -> {
                    if (version != generation) { return; }
                    LivePlayLauncher.closeActive();
                    if (advisor != null && selected.equals(activeSettings)) {
                        advisor.resumeLive();
                        monitor.show();
                        installed[0] = true;
                        return;
                    }
                    if (advisor != null) { advisor.close(); }
                    if (monitor != null) { monitor.dispose(); }
                    advisor = new LiveAdvisor(client, selected.model(), selected.effort(), Duration.ofSeconds(selected.timeout()), directory);
                    activeSettings = selected;
                    monitor = new LlmMonitor(selected.model(), selected.effort(), this::pause, enable::doClick);
                    advisor.setMonitor(monitor);
                    LiveDecisionBridge.install(advisor);
                    installed[0] = true;
                });
                return installed[0] ? "Advisor enabled. Start a local game with an AI opponent. ChatGPT plan usage applies."
                        : "Paused. Forge AI is in control.";
            });
        });
        actions.add(enable);
        JButton pause = new JButton("Pause / use Forge AI");
        pause.addActionListener(e -> pause());
        actions.add(pause);
        JButton showMonitor = new JButton("Show activity");
        showMonitor.addActionListener(e -> { if (monitor != null) { monitor.show(); } else { status.setText("Enable the advisor to open its activity window."); } });
        actions.add(showMonitor); center.add(actions);
        center.add(new JLabel("<html>Activity includes the AI's hand. Close setup to keep playing;<br>the advisor stays enabled until paused or Forge exits.</html>"));
        content.add(center, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content); frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.pack(); frame.setMinimumSize(new Dimension(760, 440)); frame.setLocationByPlatform(true);
        work(() -> {
            for (var entry : auth.profiles()) {
                var profile = entry.getAsJsonObject();
                if (profile.has("active") && profile.get("active").getAsBoolean()) {
                    return "Saved ChatGPT connection found. Enable the advisor to check it and start playing.";
                }
            }
            return "Sign in with ChatGPT, then refresh models and enable the advisor.";
        });
    }

    private ProviderClient provider() { return ProviderClient.chatGpt(auth, null); }
    private static Path dataDirectory() {
        return dataDirectory(Path.of(System.getProperty("user.dir")), Path.of(System.getProperty("user.home")),
                System.getProperty("forge.llm.dataDir"));
    }
    static Path dataDirectory(Path workingDirectory, Path userDirectory, String configured) {
        if (configured != null) { return Path.of(configured).toAbsolutePath(); }
        Path working = workingDirectory.toAbsolutePath();
        for (Path parent = working; parent != null; parent = parent.getParent()) {
            if (Files.exists(parent.resolve("tools/llm-advisor.ps1"))) { return parent.resolve(".forge-llm"); }
        }
        return userDirectory.toAbsolutePath().resolve(".forge-llm");
    }
    private void pause() {
        generation++;
        if (advisor != null) { advisor.pause(); }
        LivePlayLauncher.pauseActive();
        status.setText("Paused. Forge AI is in control.");
    }
    private interface Job { String run() throws Exception; }
    private JButton button(String title, boolean pauseFirst, Job job) {
        JButton button = new JButton(title); networkButtons.add(button);
        button.addActionListener(e -> { if (pauseFirst) { pause(); } work(job); });
        return button;
    }
    private void work(Job job) {
        int version = generation;
        networkButtons.forEach(button -> button.setEnabled(false));
        status.setText("Working… Complete browser sign-in if prompted.");
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return job.run(); }
            @Override protected void done() {
                networkButtons.forEach(button -> button.setEnabled(true));
                try { String message = get(); if (version == generation) { status.setText(message); } }
                catch (Exception failure) {
                    if (version == generation) {
                        status.setText("Could not connect. Sign in with ChatGPT, check your network, then try again.");
                    }
                }
            }
        }.execute();
    }
    public static void show() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(DesktopAdvisor::show); return; }
        if (instance == null) { instance = new DesktopAdvisor(); }
        instance.frame.setState(JFrame.NORMAL);
        instance.frame.setVisible(true); instance.frame.toFront();
    }
}
