package forge.llm;

import com.google.gson.JsonObject;
import forge.ai.llm.PrivateFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** User-owned download cache. No Wizards document is shipped in source or application resources. */
public final class RulesRepository {
    static final URI PAGE = URI.create("https://magic.wizards.com/en/rules");
    static final Duration CHECK_INTERVAL = Duration.ofDays(1);
    static final int MAX_BYTES = 4_000_000;
    private static final Pattern LINK = Pattern.compile(
            "https://media\\.wizards\\.com/\\d{4}/downloads/MagicCompRules(?:%20|[ _-])?(\\d{8})\\.txt", Pattern.CASE_INSENSITIVE);
    private static final Pattern EFFECTIVE = Pattern.compile("These rules are effective as of ([A-Za-z]+ \\d{1,2}, \\d{4})\\.");
    private static final DateTimeFormatter EFFECTIVE_DATE = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH);
    private static final ConcurrentHashMap<Path, RulesRepository> ACTIVE = new ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService UPDATES = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "forge-rules-update"); thread.setDaemon(true); return thread;
    });

    @FunctionalInterface interface Download { String get(URI uri) throws IOException; }
    private final Path cacheFile;
    private final Download download;
    private final Clock clock;
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    public interface Subscription extends AutoCloseable { @Override void close(); }
    private static final class Listener {
        private Consumer<String> observer;
        Listener(Consumer<String> observer) { this.observer = observer; }
        synchronized void accept(String message) {
            if (observer != null) {
                try { observer.accept(message); } catch (RuntimeException ignored) { /* UI observers cannot break cache updates. */ }
            }
        }
        synchronized void close() { observer = null; }
    }
    private volatile RulesReference current;
    private volatile String status = "Rules reference unavailable; lookup tools are disabled.";
    private volatile JsonObject cached;
    private long nextCheck;

    public static RulesRepository start(Path directory) {
        Path key = directory.toAbsolutePath().normalize();
        return ACTIVE.computeIfAbsent(key, path -> {
            RulesRepository repository = new RulesRepository(path, RulesRepository::download, Clock.systemUTC(), null);
            UPDATES.execute(repository::refresh);
            return repository;
        });
    }

    RulesRepository(Path directory, Download download, Clock clock, Consumer<String> observer) {
        cacheFile = directory.resolve("rules-cache.json");
        this.download = download; this.clock = clock;
        try {
            if (Files.size(cacheFile) > MAX_BYTES * 2L) { throw new IOException("Overlong rules cache."); }
            JsonObject record = LlmHttp.object(new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8));
            RulesReference reference = validate(record.get("text").getAsString(), URI.create(record.get("source").getAsString()));
            if (!reference.edition().equals(record.get("edition").getAsString())) { throw new IOException("Rules cache edition mismatch."); }
            cached = record; current = reference;
            long lastAttempt = record.get("lastAttemptAt").getAsLong();
            // A future timestamp (for example after a clock correction) must not suppress updates indefinitely.
            if (lastAttempt <= clock.millis()) { nextCheck = lastAttempt + CHECK_INTERVAL.toMillis(); }
            status = "Rules reference ready: " + reference.edition() + " (local cache).";
        } catch (IOException | RuntimeException ignored) { /* Missing/corrupt cache means no tools until a valid download. */ }
        if (observer != null) { subscribe(observer); }
    }

    /** UI owners must close their subscription when their window/activity is destroyed. */
    public Subscription subscribe(Consumer<String> observer) {
        Listener listener = new Listener(java.util.Objects.requireNonNull(observer));
        observers.add(listener);
        listener.accept(status);
        return () -> { listener.close(); observers.remove(listener); };
    }

    RulesReference current() { return current; }
    public String status() { return status; }

    public String details() {
        return status + "\n\nRules edition: " + (current == null ? "Unavailable" : current.edition())
                + "\nDownloaded: " + timestamp("downloadedAt")
                + "\nLast successful check: " + timestamp("checkedAt");
    }

    private String timestamp(String field) {
        if (cached == null || !cached.has(field)) { return "Unknown"; }
        return java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(cached.get(field).getAsLong()));
    }

    private final java.util.concurrent.atomic.AtomicBoolean manualCheck = new java.util.concurrent.atomic.AtomicBoolean();

    /** Bypass the daily interval without blocking the UI or queuing duplicate requests. */
    public void checkForUpdates(Runnable completed) {
        if (!manualCheck.compareAndSet(false, true)) { return; }
        UPDATES.execute(() -> {
            try {
                synchronized (this) { nextCheck = 0; refresh(); }
            } finally {
                manualCheck.set(false);
                completed.run();
            }
        });
    }

    synchronized void refresh() {
        if (clock.millis() < nextCheck) { publish(status); return; }
        nextCheck = clock.millis() + CHECK_INTERVAL.toMillis();
        publish(current == null ? "Downloading Magic rules; lookup tools remain disabled until ready."
                : "Checking for an updated Magic rules edition…");
        try {
            URI latest = latestSource(download.get(PAGE));
            if (cached == null || !latest.toString().equals(cached.get("source").getAsString())) {
                String text = download.get(latest);
                RulesReference reference = validate(text, latest);
                JsonObject record = new JsonObject(); record.addProperty("schemaVersion", 1);
                record.addProperty("source", latest.toString()); record.addProperty("edition", reference.edition());
                record.addProperty("text", text); record.addProperty("lastAttemptAt", clock.millis());
                record.addProperty("downloadedAt", clock.millis());
                record.addProperty("checkedAt", clock.millis());
                // Persist the entire validated document and metadata atomically before publishing it.
                PrivateFiles.write(cacheFile, record.toString());
                cached = record; current = reference;
            } else {
                JsonObject record = cached.deepCopy();
                record.addProperty("checkedAt", clock.millis());
                record.addProperty("lastAttemptAt", clock.millis());
                PrivateFiles.write(cacheFile, record.toString()); cached = record;
            }
            publish("Rules reference ready: " + current.edition() + " (checked against Wizards). ");
        } catch (IOException | RuntimeException e) {
            try { saveAttempt(); } catch (IOException ignored) { /* Keep the last valid cache and reference. */ }
            publish(current == null ? "Rules download failed; no valid local copy. Lookup tools are disabled."
                    : "Rules update unavailable; using cached edition " + current.edition() + ".");
        }
    }

    private void saveAttempt() throws IOException {
        if (cached == null) { return; }
        JsonObject record = cached.deepCopy(); record.addProperty("lastAttemptAt", clock.millis());
        PrivateFiles.write(cacheFile, record.toString()); cached = record;
    }

    private void publish(String message) {
        status = message;
        for (Listener listener : observers) { listener.accept(message); }
    }

    static URI latestSource(String html) throws IOException {
        if (html.length() > MAX_BYTES) { throw new IOException("Overlong rules page."); }
        var matcher = LINK.matcher(html.replace("\\/", "/").replace("\\u0020", "%20"));
        LocalDate newest = null; URI source = null;
        while (matcher.find()) {
            try {
                LocalDate edition = LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE);
                if (newest == null || edition.isAfter(newest)) {
                    newest = edition; source = URI.create(matcher.group().replace(" ", "%20"));
                }
            } catch (RuntimeException ignored) { /* Ignore malformed date links rather than guessing URLs. */ }
        }
        if (source == null) { throw new IOException("Wizards rules page has no dated Comprehensive Rules TXT link."); }
        return source;
    }

    static RulesReference validate(String text, URI source) throws IOException {
        var link = LINK.matcher(source.toString());
        var effective = EFFECTIVE.matcher(text);
        if (!link.matches() || !effective.find() || text.length() > MAX_BYTES
                || !text.contains("Magic: The Gathering Comprehensive Rules")
                || !text.contains("903.1.") || !text.contains("\nGlossary") || !text.contains("\nCredits")) {
            throw new IOException("Invalid or incomplete Comprehensive Rules document.");
        }
        try {
            LocalDate date = LocalDate.parse(link.group(1), DateTimeFormatter.BASIC_ISO_DATE);
            if (!date.equals(LocalDate.parse(effective.group(1), EFFECTIVE_DATE))) { throw new IOException("Rules effective date differs from download version."); }
            RulesReference reference = new RulesReference(text, date.toString(), source.toString());
            if (reference.size() < 1000) { throw new IOException("Incomplete rules index."); }
            return reference;
        } catch (RuntimeException e) { throw new IOException("Invalid rules edition or index.", e); }
    }

    private static String download(URI uri) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !("magic.wizards.com".equals(uri.getHost()) || "media.wizards.com".equals(uri.getHost()))) {
                throw new IOException("Rules downloads are restricted to official Wizards HTTPS hosts.");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "text/html, text/plain");
            connection.setRequestProperty("User-Agent", "Forge-LLM-Rules/1.0");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) { throw new IOException("Rules redirect has no destination."); }
                    uri = uri.resolve(location); continue;
                }
                if (status != 200) { throw new IOException("Rules download HTTP " + status + "."); }
                try (var input = connection.getInputStream(); var out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (out.size() + count > MAX_BYTES || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                            throw new IOException("Rules download exceeded its size or time limit.");
                        }
                        out.write(buffer, 0, count);
                    }
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects downloading rules.");
    }
}
