package forge.llm;

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
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Desktop setup and lifecycle for the shared live advisor. */
public final class DesktopAdvisor {
    private static DesktopAdvisor instance;
    private final Path directory = dataDirectory();
    private final ChatGptAuth auth = new ChatGptAuth(directory.resolve("connections"));
    private final JFrame frame = new JFrame("LLM Advisor");
    private final JTextArea status = new JTextArea("Sign in to ChatGPT to get started.", 3, 60);
    private final JComboBox<String> model = new JComboBox<>(new String[]{AdvisorSettings.DEFAULT_MODEL});
    private final JComboBox<String> effort = new JComboBox<>(AdvisorSettings.EFFORTS.toArray(new String[0]));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(60, 1, 300, 5));
    private final List<JButton> networkButtons = new ArrayList<>();
    private final AdvisorController controller;
    private LlmMonitor monitor;
    private AdvisorSettings monitorSettings;

    private DesktopAdvisor() {
        controller = new AdvisorController(directory, auth, RulesRepository.start(directory.resolve("rules")),
                SwingUtilities::invokeLater, new AdvisorController.View() {
            @Override public void status(String message) { status.setText(message); }
            @Override public void busy(boolean value) { networkButtons.forEach(button -> button.setEnabled(!value)); }
            @Override public void beforeEnable(AdvisorSettings settings) throws java.io.IOException {
                PrivateFiles.write(directory.resolve("desktop-settings.json"),
                        new DesktopAdvisorSettings(settings.model(), settings.effort(), settings.timeoutSeconds()).json());
                LivePlayLauncher.closeActive();
            }
            @Override public LiveAdvisor.Monitor monitor(AdvisorSettings settings) {
                if (monitor == null || !settings.equals(monitorSettings)) {
                    if (monitor != null) { monitor.dispose(); }
                    monitor = new LlmMonitor(settings.model(), settings.effort(), DesktopAdvisor.this::pause, DesktopAdvisor.this::enable);
                    monitorSettings = settings;
                }
                monitor.show(); return monitor;
            }
        });
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
        account.add(button("Sign in with ChatGPT", () -> {
            LivePlayLauncher.pauseActive(); controller.signIn(profiles -> { });
        }));
        account.add(button("Refresh models", () -> controller.models(DesktopAdvisorSettings.DEFAULT.runtime(), catalog -> {
            Object selected = model.getSelectedItem();
            model.removeAllItems();
            catalog.forEach(item -> model.addItem(item.getAsJsonObject().get("id").getAsString()));
            model.setSelectedItem(selected);
        })));
        account.add(button("Sign out", () -> {
            LivePlayLauncher.pauseActive(); controller.signOut(profiles -> { });
        }));
        center.add(account);
        JPanel settings = new JPanel(new GridLayout(3, 2, 12, 12));
        settings.setBorder(BorderFactory.createTitledBorder("Advisor settings"));
        settings.add(new JLabel("Model")); settings.add(model);
        settings.add(new JLabel("Reasoning effort")); settings.add(effort);
        settings.add(new JLabel("Request timeout (seconds)")); settings.add(timeout);
        center.add(settings);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton enable = button("Enable advisor", this::enable);
        actions.add(enable);
        JButton pause = new JButton("Pause / use Forge AI");
        pause.addActionListener(e -> pause());
        actions.add(pause);
        JButton cancel = new JButton("Cancel connection request");
        cancel.addActionListener(e -> controller.cancel());
        actions.add(cancel);
        JButton showMonitor = new JButton("Show activity");
        showMonitor.addActionListener(e -> { if (monitor != null) { monitor.show(); } else { status.setText("Enable the advisor to open its activity window."); } });
        actions.add(showMonitor); center.add(actions);
        center.add(new JLabel("<html>Activity includes the AI's hand. Close setup to keep playing;<br>the advisor stays enabled until paused or Forge exits.</html>"));
        content.add(center, BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        frame.setContentPane(content); frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.pack(); frame.setMinimumSize(new Dimension(760, 440)); frame.setLocationByPlatform(true);
        controller.profiles(profiles -> {
            boolean connected = false;
            for (var item : profiles) {
                var profile = item.getAsJsonObject();
                connected |= profile.get("active").getAsBoolean() && profile.get("usingChatGptPlan").getAsBoolean();
            }
            status.setText(connected ? "Saved ChatGPT connection found. Enable the advisor to check it and start playing."
                    : "Sign in with ChatGPT, then refresh models and enable the advisor.");
        });
    }

    private void enable() {
        try {
            timeout.commitEdit();
            AdvisorSettings selected = new DesktopAdvisorSettings(String.valueOf(model.getEditor().getItem()),
                    String.valueOf(effort.getSelectedItem()), (Integer) timeout.getValue()).runtime();
            LivePlayLauncher.pauseActive();
            controller.enable(selected);
        } catch (Exception invalid) { status.setText("Choose a model, reasoning effort, and timeout between 1 and 300 seconds."); }
    }

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
        LivePlayLauncher.pauseActive();
        controller.pause();
    }
    private JButton button(String title, Runnable action) {
        JButton button = new JButton(title); networkButtons.add(button);
        button.addActionListener(e -> action.run());
        return button;
    }
    public static void show() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(DesktopAdvisor::show); return; }
        if (instance == null) { instance = new DesktopAdvisor(); }
        instance.frame.setState(JFrame.NORMAL);
        instance.frame.setVisible(true); instance.frame.toFront();
    }
}
