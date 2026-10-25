package forge.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Fixed provider endpoints, finite deadlines, no automatic retries or raw error-body logging. */
final class LlmHttp {
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private LlmHttp() { }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json, text/event-stream")
                .header("User-Agent", "Forge-LLM-Advisor/0.1");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            request.header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        CompletableFuture<HttpResponse<String>> pending = CLIENT.sendAsync(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // Includes reading the entire stream: periodic deltas cannot extend the deadline forever.
            HttpResponse<String> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "http_" + response.statusCode();
                try {
                    JsonObject error = object(response.body());
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
                    if (response.statusCode() == 400 && bearer != null && error.has("message")
                            && error.get("message").isJsonPrimitive()) {
                        String message = error.get("message").getAsString().replace(bearer, "[redacted]")
                                .replaceAll("[\\r\\n\\t]", " ");
                        code += "; " + message.substring(0, Math.min(message.length(), 240));
                    }
                } catch (Exception ignored) {
                    // Error messages can contain echoed credentials or prompts. Only expose safe codes.
                }
                throw new IOException("Provider request failed (" + code + ").");
            }
            if (response.body().length() > 2_000_000) {
                throw new IOException("Provider response exceeded the prototype size limit.");
            }
            return response.body();
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Provider request interrupted.");
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Provider request timed out.");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Provider connection failed.");
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }
}
