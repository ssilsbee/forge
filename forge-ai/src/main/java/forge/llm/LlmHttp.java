package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Fixed provider endpoints, finite deadlines, no automatic retries or raw error-body logging. */
final class LlmHttp {
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final ExecutorService NETWORK = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), job -> {
                Thread thread = new Thread(job, "forge-llm-http");
                thread.setDaemon(true);
                return thread;
            });

    private LlmHttp() { }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    static JsonObject object(String text) throws IOException {
        try {
            JsonObject result = JSON.fromJson(text, JsonObject.class);
            if (result == null) {
                throw new IOException("Empty JSON response.");
            }
            return result;
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON response.");
        }
    }

    static String request(URI uri, String method, String body, String contentType,
                          String bearer, Duration timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int) Math.min(10_000, timeout.toMillis()));
        connection.setReadTimeout((int) timeout.toMillis());
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setRequestProperty("User-Agent", "Forge-LLM-Advisor/0.1");
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            connection.setRequestProperty("Content-Type", contentType);
            connection.setDoOutput(true);
        }
        Future<String> pending;
        try {
            pending = NETWORK.submit(() -> {
                if (body != null) {
                    try (var out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
                }
                int status = connection.getResponseCode();
                String response = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
                if (status < 200 || status >= 300) { throw error(status, response, bearer); }
                return response;
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            connection.disconnect();
            throw new IOException(requestStage(uri) + " could not start because the provider network workers are busy.", e);
        }
        try {
            // Includes reading the entire stream: periodic deltas cannot extend the deadline forever.
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(requestStage(uri) + " was interrupted.", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException(requestStage(uri) + " timed out contacting " + safeHost(uri) + ".", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String stage = requestStage(uri);
            if (cause instanceof IOException io && io.getMessage() != null) {
                if (io.getMessage().startsWith("Provider request failed (")) {
                    // The HTTP error body has already been reduced to safe status/code fields.
                    throw new IOException(stage + ": " + io.getMessage(), io);
                }
                if (io.getMessage().equals("Provider response exceeded the prototype size limit.")) {
                    throw new IOException(stage + ": " + io.getMessage(), io);
                }
            }
            throw new IOException(networkFailure(uri, cause), cause);
        } finally {
            pending.cancel(true);
            connection.disconnect();
        }
    }

    private static String requestStage(URI uri) {
        String host = safeHost(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if ("auth.openai.com".equals(host)) {
            if (path.endsWith("/oauth/token")) { return "ChatGPT token exchange"; }
            if (path.contains("jwks") || path.endsWith("openid-configuration")) { return "ChatGPT sign-in verification"; }
            return "ChatGPT sign-in setup";
        }
        if ("api.openai.com".equals(host)) { return "OpenAI model request"; }
        if ("openrouter.ai".equals(host)) { return "OpenRouter model request"; }
        return "Provider request";
    }

    private static String safeHost(URI uri) {
        String host = uri.getHost();
        if (host == null) { return "provider"; }
        host = host.toLowerCase(java.util.Locale.ROOT);
        return "auth.openai.com".equals(host) || "api.openai.com".equals(host) || "openrouter.ai".equals(host)
                ? host : "provider";
    }

    private static String networkFailure(URI uri, Throwable cause) {
        String stage = requestStage(uri);
        String host = safeHost(uri);
        if (hasCause(cause, java.net.UnknownHostException.class)) {
            return stage + " could not resolve " + host + ". Check Private DNS, VPN, or network filtering.";
        }
        if (hasCause(cause, javax.net.ssl.SSLException.class)) {
            return stage + " failed its secure TLS connection to " + host + ". Check device date/time or certificate filtering.";
        }
        if (hasCause(cause, java.net.SocketTimeoutException.class)) {
            return stage + " timed out contacting " + host + ". Check the connection or network filtering.";
        }
        if (hasCause(cause, java.net.ConnectException.class) || hasCause(cause, java.net.SocketException.class)) {
            return stage + " could not connect to " + host + ". Check Forge's network access, VPN, firewall, or filtering.";
        }
        if (hasCause(cause, SecurityException.class)) {
            return "Android blocked the " + stage + ". Check Forge's network access in app settings.";
        }
        String kind = cause.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "");
        if (kind.isEmpty()) { kind = "unknown error"; }
        return stage + " failed during network I/O (" + kind + "; " + host + ").";
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) { return true; }
            if (current.getCause() == current) { break; }
        }
        return false;
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) { return ""; }
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > 2_000_000) { throw new IOException("Provider response exceeded the prototype size limit."); }
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static IOException error(int status, String response, String bearer) {
                String code = "http_" + status;
                try {
                    JsonObject error = object(response);
                    if (error.has("error") && error.get("error").isJsonObject()) {
                        error = error.getAsJsonObject("error");
                        for (String field : new String[] {"code", "param", "type"}) {
                            if (error.has(field) && error.get(field).isJsonPrimitive()) {
                                String value = error.get(field).getAsString();
                                if (value.matches("[a-zA-Z0-9_.-]{1,100}")) { code += "; " + field + "=" + value; }
                            }
                        }
                    } else if (error.has("error") && error.get("error").isJsonPrimitive()) {
                        String value = error.get("error").getAsString();
                        if (value.matches("[a-zA-Z0-9_-]{1,100}")) { code += "; code=" + value; }
                    }
                    // Invalid-parameter diagnostics help repair a request without dumping a response body.
                    // Auth token exchanges have no bearer here and never expose their messages.
                    if (status == 400 && bearer != null && error.has("message")
                            && error.get("message").isJsonPrimitive()) {
                        String message = error.get("message").getAsString().replace(bearer, "[redacted]")
                                .replaceAll("[\\r\\n\\t]", " ");
                        code += "; " + message.substring(0, Math.min(message.length(), 240));
                    }
                } catch (Exception ignored) {
                    // Error messages can contain echoed credentials or prompts. Only expose safe codes.
                }
                return new IOException("Provider request failed (" + code + ").");
    }
}
