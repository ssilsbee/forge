package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded, read-only view of final model I/O. Never displays credentials or hidden reasoning. */
final class LlmMonitor implements LiveAdvisor.Monitor {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final DefaultListModel<String> history = new DefaultListModel<>();
    private final JList<String> list = new JList<>(history);
    private final JTextArea input = area();
    private final JTextArea output = area();
    private final JTextArea events = area();
    private final JLabel status = new JLabel("Ready. Start a local Constructed match with one human and one AI.");
    private final JFrame frame = new JFrame("Forge LLM Monitor — GPT-6 Luna high");
    private static final class Entry { String label; String input = ""; String output = "Waiting for response…"; }

    LlmMonitor(String model, String effort, Runnable pause) {
        frame.setTitle("Forge LLM Monitor — " + model + " / " + effort);
        JButton fallback = new JButton("Use Forge / cancel request");
        fallback.addActionListener(e -> pause.run());
        JButton resume = new JButton("Enable Luna");
        resume.addActionListener(e -> LivePlayLauncher.resume());
        JPanel buttons = new JPanel();
        buttons.add(fallback);
        buttons.add(resume);
        buttons.add(new JLabel("Input reveals the AI hand. Minimize this window to hide it."));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Input", new JScrollPane(input));
        tabs.addTab("Output", new JScrollPane(output));
        tabs.addTab("Moves and status", new JScrollPane(events));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), tabs);
        split.setDividerLocation(215);
        list.addListSelectionListener(e -> render());
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> owner, Object value, int index,
                    boolean selected, boolean focused) {
                Entry entry = entries.get(value);
                return super.getListCellRendererComponent(owner, entry == null ? value : entry.label, index, selected, focused);
            }
        });
        frame.add(status, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);
        frame.setSize(1080, 720);
        frame.setLocationByPlatform(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) { frame.setState(JFrame.ICONIFIED); }
        });
        frame.setVisible(true);
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return area;
    }

    @Override public boolean isDisplayThread() { return SwingUtilities.isEventDispatchThread(); }

    @Override public void request(String id, String instructions, JsonObject question) {
        String text = "INSTRUCTIONS\n" + instructions + "\n\nUSER MESSAGE\n" + JSON.toJson(question);
        SwingUtilities.invokeLater(() -> {
            Entry entry = new Entry();
            entry.label = question.has("turn") ? "Turn " + question.get("turn") + " · "
                    + question.get("phase").getAsString() + " · " + id.substring(0, 6) : "Deck strategy";
            entry.input = text;
            entries.put(id, entry);
            history.addElement(id);
            while (history.size() > 60) { entries.remove(history.remove(0)); }
            list.setSelectedIndex(history.size() - 1);
            render();
        });
    }

    @Override public void response(String id, JsonObject response) {
        JsonObject metadata = response.deepCopy();
        metadata.remove("input"); // exact input has its own tab
        String text = response.has("responseText") ? "MODEL FINAL ANSWER\n" + response.get("responseText").getAsString()
                + "\n\nSTATUS: " + response.get("status").getAsString()
                + "\nSELECTED ACTION: " + response.get("recommendedActionId").getAsString()
                + "\nRESPONSE TIME: " + response.get("elapsedMillis") + " ms"
                + "\n\nSee Moves and status for engine execution or fallback.\n\nCOMPLETE REPORT\n" + JSON.toJson(metadata)
                : response.has("summary") ? "DECK STRATEGY\n" + JSON.toJson(response.get("summary"))
                + "\n\nMETADATA (may have been loaded from cache)\n" + JSON.toJson(metadata) : JSON.toJson(metadata);
        SwingUtilities.invokeLater(() -> {
            Entry entry = entries.get(id);
            if (entry != null) { entry.output = text; }
            render();
        });
    }

    @Override public void status(String id, String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            events.append((id.isBlank() ? "" : id + ": ") + message + "\n");
            if (events.getDocument().getLength() > 40_000) { events.setText(events.getText().substring(20_000)); }
            events.setCaretPosition(events.getDocument().getLength());
        });
    }

    private void render() {
        Entry entry = entries.get(list.getSelectedValue());
        if (entry == null) { return; }
        input.setText(entry.input);
        output.setText(entry.output);
        input.setCaretPosition(0);
        output.setCaretPosition(0);
    }
}
