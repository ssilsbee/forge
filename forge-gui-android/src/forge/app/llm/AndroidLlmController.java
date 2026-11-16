package forge.app.llm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import forge.llm.AdvisorController;
import forge.llm.AdvisorSettings;
import forge.llm.ChatGptAuth;
import forge.llm.LiveAdvisor;
import forge.llm.ProviderClient;
import forge.llm.RulesRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-owned settings and monitor. A dialog overlays the game without stopping its render loop. */
public final class AndroidLlmController implements LiveAdvisor.Monitor, AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Activity activity;
    private final Path directory;
    private final Path settingsPath;
    private final AndroidSecrets secrets = new AndroidSecrets();
    private final ChatGptAuth auth;
    private final RulesRepository rules;
    private final RulesRepository.Subscription rulesSubscription;
    private final SignInForegroundGate signInForeground = new SignInForegroundGate();
    private final AdvisorController controller;
    private final Map<String, Entry> entries = new LinkedHashMap<>(); // Android main thread only
    private final StringBuilder events = new StringBuilder();
    private JsonObject settings = new JsonObject();
    private boolean closed;
    private Dialog dialog;
    private LinearLayout body;
    private TextView summary, text, accountInfo;
    private Spinner provider, effort, history;
    private EditText model, apiKey;
    private int tab;
    private String selected;
    private String latest = "LLM is paused. Choose a connection, test it, then enable LLM play.";
    private String account = "ChatGPT: not connected on this device";
    private String insightReport = "Enable the advisor to collect gameplay timing and decision coverage.";
    private static final class Entry { String label, input, output = "Waiting for response…"; }

    public AndroidLlmController(Activity activity) {
        this.activity = activity;
        // noBackupFilesDir keeps tokens and private hands out of Android backup and shared storage.
        directory = activity.getNoBackupFilesDir().toPath().resolve("llm");
        settingsPath = directory.resolve("settings.enc");
        rules = RulesRepository.start(directory.resolve("rules"));
        rulesSubscription = rules.subscribe(message -> status("", message));
        auth = new ChatGptAuth(directory.resolve("connections"), uri -> activity.runOnUiThread(() -> {
            if (closed) { return; }
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))); }
            catch (Exception e) { cancelOperation(); status("", "No browser found. Install a browser to continue with ChatGPT."); }
        }), secrets, Duration.ofMinutes(15), () -> {
            status("", "Authorization received. Return to Forge to finish connecting.");
            signInForeground.awaitForeground(Duration.ofMinutes(2));
            status("", "Finishing ChatGPT connection in Forge…");
        });
        try { settings = secrets.read(settingsPath); }
        catch (IOException e) { latest = e.getMessage(); }
        controller = new AdvisorController(directory, auth, rules, activity::runOnUiThread, new AdvisorController.View() {
            @Override public void status(String message) { AndroidLlmController.this.status("", message); }
            @Override public void paused() throws IOException { rememberEnabled(false); }
            @Override public void beforeEnable(AdvisorSettings settings) throws IOException { rememberEnabled(true); }
            @Override public LiveAdvisor.Monitor monitor(AdvisorSettings settings) { return AndroidLlmController.this; }
        });
        controller.profiles(profiles -> {
            refreshAccount(profiles);
            if (settings.has("enabled") && settings.get("enabled").isJsonPrimitive()
                    && settings.get("enabled").getAsJsonPrimitive().isBoolean() && settings.get("enabled").getAsBoolean()) {
                connect(settings.deepCopy(), true);
            }
        });
    }

    public void setForeground(boolean foreground) { signInForeground.setForeground(foreground); }

    public void showRulesSettings() {
        if (closed || activity.isFinishing()) { return; }
        TextView details = label(rules.details()
                + "\n\nForge checks automatically on startup, at most once daily. Manual checks run immediately. "
                + "Older cached copies may have unknown download/check dates until the next update.", 15);
        details.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(details);
        android.app.AlertDialog window = new android.app.AlertDialog.Builder(activity)
                .setTitle("Magic Comprehensive Rules")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .setPositiveButton("Check for updates", null)
                .create();
        // Use the platform theme's text color inside the native settings dialog.
        details.setTextColor(new TextView(activity).getCurrentTextColor());
        window.setOnShowListener(ignored -> window.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Button check = window.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            check.setEnabled(false);
            details.setText("Checking Wizards for the newest rules…");
            rules.checkForUpdates(() -> activity.runOnUiThread(() -> {
                if (closed || !window.isShowing()) { return; }
                details.setText(rules.details());
                check.setEnabled(true);
            }));
        }));
        window.show();
    }

    public void show() {
        if (dialog != null && dialog.isShowing()) { return; }
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(22, 28, 34));
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout heading = row();
        TextView title = label("LLM Advisor", 22);
        LinearLayout tabs = row();
        String[] names = {"Connect", "Input", "Output", "Status", "Insights"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button button = button(names[i], () -> renderTab(index));
            button.setTextSize(12);
            button.setMinimumHeight(0);
            if (landscape) {
                button.setSingleLine(true);
                button.setMinWidth(0);
                button.setMinimumWidth(0);
                button.setPadding(dp(16), 0, dp(16), 0);
                tabs.addView(button, new LinearLayout.LayoutParams(-2, dp(42)));
            } else {
                button.setPadding(0, 0, 0, 0);
                tabs.addView(button, new LinearLayout.LayoutParams(0, dp(45), 1));
            }
        }
        if (landscape) {
            title.setTextSize(20);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(tabs, new LinearLayout.LayoutParams(-2, dp(42)));
            Button back = button("Back to game", () -> dialog.dismiss());
            back.setTextSize(12);
            back.setSingleLine(true);
            back.setMinWidth(0);
            back.setMinimumWidth(0);
            back.setPadding(dp(16), 0, dp(16), 0);
            back.setMinimumHeight(0);
            heading.addView(back, new LinearLayout.LayoutParams(-2, dp(42)));
        } else {
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(button("Back to game", () -> dialog.dismiss()));
        }
        root.addView(heading);
        summary = label(latest, 13);
        root.addView(summary);
        if (!landscape) { root.addView(tabs); }
        body = column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = row();
        controls.addView(button("Pause / use Forge", () -> { cancelOperation(); pause(); }), new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Enable LLM", this::enable), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);
        dialog.setContentView(root);
        dialog.setOnDismissListener(ignored -> { apiKey = null; model = null; provider = null; effort = null; text = null; history = null; });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        renderTab(tab);
    }

    private void renderTab(int next) {
        if (provider != null && tab == 0 && next != 0) {
            try { save(); } catch (IOException e) { status("", e.getMessage()); return; }
        }
        tab = next;
        body.removeAllViews();
        history = null; text = null; accountInfo = null; provider = null; effort = null; model = null; apiKey = null;
        if (tab == 0) { renderSettings(); return; }
        if (tab == 1 || tab == 2) {
            history = new Spinner(activity);
            boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            if (landscape) {
                history.setPadding(0, 0, 0, 0);
                history.setMinimumHeight(0);
                body.addView(history, new LinearLayout.LayoutParams(-1, dp(48)));
            } else {
                body.addView(history);
            }
            updateHistory();
            history.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                    selected = new ArrayList<>(entries.keySet()).get(position);
                    renderText();
                }
                @Override public void onNothingSelected(AdapterView<?> p) { }
            });
        }
        ScrollView scroll = new ScrollView(activity);
        text = label("", 13);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        text.setPadding(dp(4), dp(8), dp(4), dp(8));
        scroll.addView(text);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderText();
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = column();
        scroll.addView(form);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        form.addView(label("Connection", 15));
        provider = spinner(new String[] {"ChatGPT subscription", "OpenRouter"});
        provider.setSelection(value("provider", "chatgpt").equals("chatgpt") ? 0 : 1);
        form.addView(provider);
        form.addView(label("Model", 15));
        model = edit(false, "Model ID");
        form.addView(model);
        String initial = chosenProvider();
        model.setText(value(initial + "Model", initial.equals("chatgpt") ? AdvisorSettings.DEFAULT_MODEL : ""));
        form.addView(label("Reasoning effort", 15));
        String[] efforts = AdvisorSettings.EFFORTS.toArray(new String[0]);
        effort = spinner(efforts);
        effort.setSelection(java.util.Arrays.asList(efforts).indexOf(value("effort", "high")));
        form.addView(effort);
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private String previous = initial;
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                String current = chosenProvider();
                if (current.equals(previous)) { return; }
                settings.addProperty(previous + "Model", model.getText().toString().trim());
                model.setText(value(current + "Model", current.equals("chatgpt") ? AdvisorSettings.DEFAULT_MODEL : ""));
                previous = current;
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        accountInfo = label(account, 13);
        form.addView(accountInfo);
        form.addView(button("Continue with ChatGPT", () -> controller.signIn(this::refreshAccount)));
        form.addView(button("Disconnect ChatGPT", () -> controller.signOut(this::refreshAccount)));
        form.addView(label(settings.has("openrouterKey") ? "OpenRouter key is saved on this device." : "OpenRouter API key", 13));
        apiKey = edit(true, "Paste key; leave blank to keep saved key");
        form.addView(apiKey);
        form.addView(button("Save settings", () -> {
            try { save(); status("", "Settings saved on this device. Enable LLM to use them."); }
            catch (IOException e) { status("", e.getMessage()); }
        }));
        form.addView(button("Forget OpenRouter key", () -> {
            pause();
            settings.remove("openrouterKey"); apiKey.setText("");
            try { secrets.write(settingsPath, settings); status("", "OpenRouter key removed."); }
            catch (IOException e) { status("", e.getMessage()); }
        }));
        form.addView(button("Test connection", this::test));
        form.addView(button("Cancel connection request", this::cancelOperation));
        form.addView(label("Input shows the AI's hand. Keep the panel closed for normal play. "
                + "LLM play covers supported actions, attacks, blocks, card choices, scry and surveil in two-player Constructed and Commander games. "
                + "Large combat declarations, banding, mana payment and damage assignment use Forge. "
                + "ChatGPT uses your plan allowance. OpenRouter uses your OpenRouter balance. "
                + "Credentials are encrypted on this device and excluded from Android backup. "
                + "Forge remembers Enable LLM and reconnects on startup. Pause / use Forge turns automatic enable off.", 13));
    }

    private String value(String key, String fallback) { return settings.has(key) ? settings.get(key).getAsString() : fallback; }
    private String chosenProvider() { return provider.getSelectedItemPosition() == 0 ? "chatgpt" : "openrouter"; }

    private void save() throws IOException {
        if (provider == null) { return; }
        String before = settings.toString();
        String name = chosenProvider();
        settings.addProperty("provider", name);
        settings.addProperty(name + "Model", model.getText().toString().trim());
        settings.addProperty("effort", effort.getSelectedItem().toString());
        String key = apiKey.getText().toString().trim();
        if (!key.isEmpty()) { settings.addProperty("openrouterKey", key); }
        secrets.write(settingsPath, settings);
        apiKey.setText("");
        if (!before.equals(settings.toString())) { pause(); }
    }

    private AdvisorSettings runtime(JsonObject config) {
        String name = config.has("provider") ? config.get("provider").getAsString() : "chatgpt";
        ProviderClient.Provider selected = switch (name) {
            case "chatgpt" -> ProviderClient.Provider.CHATGPT;
            case "openrouter" -> ProviderClient.Provider.OPENROUTER;
            default -> throw new IllegalArgumentException("Choose ChatGPT or OpenRouter.");
        };
        return new AdvisorSettings(selected,
                config.has(name + "Model") ? config.get(name + "Model").getAsString() : selected == ProviderClient.Provider.CHATGPT ? AdvisorSettings.DEFAULT_MODEL : "",
                config.has("effort") ? config.get("effort").getAsString() : "high", 60,
                config.has("openrouterKey") ? config.get("openrouterKey").getAsString() : null);
    }

    private void enable() {
        try { save(); } catch (IOException e) { status("", e.getMessage()); return; }
        connect(settings.deepCopy(), false);
    }

    private void rememberEnabled(boolean enabled) throws IOException {
        JsonObject next = settings.deepCopy();
        next.addProperty("enabled", enabled);
        secrets.write(settingsPath, next);
        settings = next;
    }

    private void connect(JsonObject config, boolean restoring) {
        try { controller.enable(runtime(config)); }
        catch (RuntimeException e) {
            status("", restoring ? "Automatic LLM connection could not start. Check the saved connection settings."
                    : "Choose a provider, model, and reasoning effort first.");
        }
    }

    private void test() {
        try {
            save();
            controller.test(runtime(settings), this);
        } catch (IOException | RuntimeException e) { status("", "Check the connection settings before testing."); }
    }

    private void refreshAccount(JsonArray profiles) {
        String message = "ChatGPT: not connected on this device";
        for (var item : profiles) {
            JsonObject profile = item.getAsJsonObject();
            if (profile.get("active").getAsBoolean() && profile.get("usingChatGptPlan").getAsBoolean()) {
                message = "ChatGPT connected" + (profile.has("email") && !profile.get("email").isJsonNull()
                        ? ": " + profile.get("email").getAsString() : ""); break;
            }
        }
        account = message;
        if (accountInfo != null) { accountInfo.setText(message); }
    }

    private void cancelOperation() { controller.cancel(); }
    private void pause() { controller.pause(); }

    @Override public boolean isDisplayThread() { return Looper.myLooper() == Looper.getMainLooper(); }
    @Override public void request(String id, String instructions, JsonObject input) {
        String exact = "INSTRUCTIONS\n" + instructions + "\n\nUSER MESSAGE\n" + JSON.toJson(input);
        String label = id.startsWith("connection-") ? "Connection check" : id.endsWith(" / deck plan") ? "Deck strategy"
                : input.has("turn") ? "Turn " + input.get("turn") + " · " + input.get("phase").getAsString() + " · " + id.substring(0, 6) : id;
        activity.runOnUiThread(() -> {
            if (closed) { return; }
            Entry entry = new Entry(); entry.label = label; entry.input = exact;
            entries.put(id, entry);
            while (entries.size() > 60) { entries.remove(entries.keySet().iterator().next()); }
            selected = id;
            updateHistory(); renderText();
        });
    }

    @Override public void response(String id, JsonObject output) {
        JsonObject report = output.deepCopy(); report.remove("input");
        String visible = report.has("responseText") ? "MODEL FINAL ANSWER\n" + report.get("responseText").getAsString() + "\n\nREPORT\n" : "";
        String result = visible + JSON.toJson(report);
        activity.runOnUiThread(() -> {
            if (closed) { return; }
            Entry entry = entries.get(id); if (entry != null) { entry.output = result; }
            renderText();
        });
    }

    @Override public void insights(String report) {
        activity.runOnUiThread(() -> {
            if (closed) { return; }
            insightReport = report;
            if (tab == 4) { renderText(); }
        });
    }

    @Override public void status(String id, String message) {
        activity.runOnUiThread(() -> {
            if (closed) { return; }
            latest = message;
            events.append(id.isEmpty() ? "" : id + ": ").append(message).append('\n');
            if (events.length() > 40_000) { events.delete(0, 20_000); }
            if (summary != null) { summary.setText(latest); }
            renderText();
        });
    }

    private void updateHistory() {
        if (history == null) { return; }
        ArrayList<String> labels = new ArrayList<>();
        for (Entry entry : entries.values()) { labels.add(entry.label); }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, labels);
        history.setAdapter(adapter);
        int index = new ArrayList<>(entries.keySet()).indexOf(selected);
        if (index >= 0) { history.setSelection(index); }
    }
    private void renderText() {
        if (text == null) { return; }
        if (tab == 3) { text.setText(events.toString()); return; }
        if (tab == 4) { text.setText(insightReport); return; }
        Entry entry = entries.get(selected);
        text.setText(entry == null ? "No requests yet. Use Test connection, or enable LLM and start a Constructed match."
                : tab == 1 ? entry.input : entry.output);
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView label(String value, int size) { TextView view = new TextView(activity); view.setText(value); view.setTextColor(Color.WHITE); view.setTextSize(size); return view; }
    private Button button(String value, Runnable action) { Button view = new Button(activity); view.setText(value); view.setOnClickListener(v -> action.run()); return view; }
    private Spinner spinner(String[] values) {
        Spinner view = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
    }
    private EditText edit(boolean secret, String hint) {
        EditText view = new EditText(activity); view.setSingleLine(true); view.setTextColor(Color.BLACK);
        view.setHintTextColor(Color.GRAY); view.setHint(hint); view.setTextSize(14);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (secret) {
            view.setOnFocusChangeListener((v, focused) -> {
                if (dialog != null) {
                    if (focused) { dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); }
                    else { dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE); }
                }
            });
        }
        return view;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    @Override public void close() {
        closed = true;
        rulesSubscription.close();
        signInForeground.close();
        controller.close();
        if (dialog != null) { dialog.dismiss(); }
    }
}
