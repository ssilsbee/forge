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
import forge.ai.llm.LiveDecisionBridge;
import forge.llm.ChatGptAuth;
import forge.llm.LiveAdvisor;
import forge.llm.ProviderClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Android-owned settings and monitor. A dialog overlays the game without stopping its render loop. */
public final class AndroidLlmController implements LiveAdvisor.Monitor, AutoCloseable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Activity activity;
    private final Path directory;
    private final Path settingsPath;
    private final AndroidSecrets secrets = new AndroidSecrets();
    private final ChatGptAuth auth;
    private final SignInForegroundGate signInForeground = new SignInForegroundGate();
    private final ExecutorService connections = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "forge-android-llm-connection");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Entry> entries = new LinkedHashMap<>(); // Android main thread only
    private final StringBuilder events = new StringBuilder();
    private JsonObject settings = new JsonObject();
    private volatile LiveAdvisor advisor;
    private volatile Future<?> operation;
    private final AtomicLong changes = new AtomicLong();
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
        connections.submit(this::refreshAccount);
        if (settings.has("enabled") && settings.get("enabled").isJsonPrimitive()
                && settings.get("enabled").getAsJsonPrimitive().isBoolean() && settings.get("enabled").getAsBoolean()) {
            connect(settings.deepCopy(), true);
        }
    }

    public void setForeground(boolean foreground) { signInForeground.setForeground(foreground); }

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
        model.setText(value(initial + "Model", initial.equals("chatgpt") ? "gpt-6-luna" : ""));
        form.addView(label("Reasoning effort", 15));
        String[] efforts = {"none", "minimal", "low", "medium", "high", "xhigh", "max"};
        effort = spinner(efforts);
        effort.setSelection(java.util.Arrays.asList(efforts).indexOf(value("effort", "high")));
        form.addView(effort);
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private String previous = initial;
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                String current = chosenProvider();
                if (current.equals(previous)) { return; }
                settings.addProperty(previous + "Model", model.getText().toString().trim());
                model.setText(value(current + "Model", current.equals("chatgpt") ? "gpt-6-luna" : ""));
                previous = current;
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        accountInfo = label(account, 13);
        form.addView(accountInfo);
        form.addView(button("Continue with ChatGPT", () -> run("Waiting for ChatGPT browser sign-in…", () -> {
            auth.login(null, false);
            refreshAccount();
            status("", "ChatGPT connected. Test the connection, then enable LLM play.");
        })));
        form.addView(button("Disconnect ChatGPT", () -> run("Disconnecting ChatGPT…", () -> {
            pause();
            boolean revoked = auth.logout(null);
            refreshAccount();
            status("", revoked ? "ChatGPT disconnected." : "Local ChatGPT tokens cleared; remote revocation could not be confirmed.");
        })));
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

    private ProviderClient client(JsonObject config) throws IOException {
        if (config.get("provider").getAsString().equals("chatgpt")) {
            boolean connected = false;
            for (var profile : auth.profiles()) { connected |= profile.getAsJsonObject().get("usingChatGptPlan").getAsBoolean(); }
            if (!connected) { throw new IOException("Continue with ChatGPT on this device first."); }
            return ProviderClient.chatGpt(auth, null);
        }
        return ProviderClient.openRouter(config.has("openrouterKey") ? config.get("openrouterKey").getAsString() : null);
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
        final long attempt = changes.get();
        run(restoring ? "Restoring the saved LLM connection…" : "Checking the selected connection and model…", () -> {
          try {
            ProviderClient providerClient = client(config);
            String selectedModel = config.get(config.get("provider").getAsString() + "Model").getAsString();
            boolean found = false;
            for (var item : providerClient.models()) { found |= selectedModel.equals(item.getAsJsonObject().get("id").getAsString()); }
            if (!found) { throw new IOException("The selected model is not listed by this connection. Check its model ID."); }
            LiveAdvisor next = new LiveAdvisor(providerClient, selectedModel, config.get("effort").getAsString(), Duration.ofSeconds(60), directory);
            next.setMonitor(this);
            activity.runOnUiThread(() -> {
                if (closed || changes.get() != attempt) { next.close(); return; }
                try { rememberEnabled(true); }
                catch (IOException e) { next.close(); status("", "Could not save the enabled setting. " + e.getMessage()); return; }
                LiveAdvisor old = advisor;
                if (old != null) { old.close(); }
                advisor = next;
                LiveDecisionBridge.install(next);
                next.resumeLive();
            });
          } catch (Exception e) {
            if (restoring) {
                throw new IOException("Automatic LLM connection failed. Forge remains in control. "
                        + (e instanceof IOException ? e.getMessage() : "Check the saved connection settings."), e);
            }
            throw e;
          }
        });
    }

    private void test() {
        try { save(); } catch (IOException e) { status("", e.getMessage()); return; }
        final JsonObject config = settings.deepCopy();
        run("Testing the selected model (uses provider allowance)…", () -> {
            ProviderClient providerClient = client(config);
            String modelId = config.get(config.get("provider").getAsString() + "Model").getAsString();
            String id = "connection-" + System.currentTimeMillis();
            String instructions = "Connection check for Forge. Return only the JSON object {\"connected\":true}.";
            JsonObject input = new JsonObject(); input.addProperty("request", "Confirm the connection.");
            request(id, instructions, input);
            long start = System.nanoTime();
            try {
                ProviderClient.Completion result = providerClient.completeWithInstructions(instructions, modelId,
                        config.get("effort").getAsString(), 8192, input, Duration.ofSeconds(60));
                JsonObject report = new JsonObject();
                report.addProperty("responseText", result.text());
                report.addProperty("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
                report.addProperty("provider", providerClient.name()); report.addProperty("model", modelId);
                report.add("usage", result.usage());
                response(id, report);
                JsonObject answer = com.google.gson.JsonParser.parseString(result.text()).getAsJsonObject();
                if (!answer.has("connected") || !answer.get("connected").getAsBoolean()) { throw new IOException("Model returned an unexpected connection-check answer."); }
                status(id, "Connection verified with " + modelId + ". View Input and Output, then enable LLM play.");
            } catch (Exception e) {
                JsonObject report = new JsonObject(); report.addProperty("status", "connection_failed");
                report.addProperty("error", e instanceof IOException ? e.getMessage() : "Connection test failed.");
                response(id, report); throw e;
            }
        });
    }

    private interface Action { void run() throws Exception; }
    private void run(String message, Action action) {
        if (operation != null && !operation.isDone()) { status("", "A connection request is already running. Cancel it first if needed."); return; }
        status("", message);
        operation = connections.submit(() -> {
            try { action.run(); }
            catch (Exception e) { if (!Thread.currentThread().isInterrupted()) { status("", e instanceof IOException ? e.getMessage() : "Connection request failed."); } }
            catch (LinkageError e) {
                android.util.Log.e("ForgeLlm", "Android runtime compatibility error", e);
                status("", "An Android compatibility error prevented the connection. See the device log.");
            }
        });
    }

    private void refreshAccount() {
        String message = "ChatGPT: not connected on this device";
        try {
            for (var item : auth.profiles()) {
                JsonObject profile = item.getAsJsonObject();
                if (profile.get("usingChatGptPlan").getAsBoolean()) {
                    message = "ChatGPT connected" + (profile.has("email") && !profile.get("email").isJsonNull()
                            ? ": " + profile.get("email").getAsString() : ""); break;
                }
            }
        } catch (IOException e) { message = e.getMessage(); }
        final String result = message;
        activity.runOnUiThread(() -> { account = result; if (accountInfo != null) { accountInfo.setText(result); } });
    }

    private void cancelOperation() {
        changes.incrementAndGet();
        Future<?> task = operation;
        if (task != null) { task.cancel(true); }
        status("", "Connection request cancelled.");
    }

    private void pause() {
        changes.incrementAndGet();
        LiveAdvisor active = advisor;
        if (active != null) { active.pause(); }
        status("", "LLM paused. Forge is in control.");
        activity.runOnUiThread(() -> {
            try { rememberEnabled(false); }
            catch (IOException e) { status("", "LLM paused, but the startup preference could not be saved. " + e.getMessage()); }
        });
    }

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
        signInForeground.close();
        cancelOperation();
        LiveAdvisor active = advisor; if (active != null) { active.close(); }
        LiveDecisionBridge.install(null);
        connections.shutdownNow();
        if (dialog != null) { dialog.dismiss(); }
    }
}
