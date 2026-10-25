package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import forge.ai.llm.PrivateFiles;

import java.awt.Desktop;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Documented Sign in with ChatGPT for local open-source clients. Own registration, never Codex tokens. */
public final class ChatGptAuth {
    private static final String ISSUER = "https://auth.openai.com";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final URI TOKEN_ENDPOINT = URI.create(ISSUER + "/api/accounts/oauth/token");
    private static final String DIRECT_SCOPE = "chatgpt.tokens.use.direct";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path directory;

    public ChatGptAuth(Path directory) {
        this.directory = directory.toAbsolutePath();
    }

    @FunctionalInterface
    private interface LockedOperation<T> { T run() throws Exception; }

    private synchronized <T> T locked(LockedOperation<T> operation) throws IOException {
        boolean existed = Files.exists(directory);
        Files.createDirectories(directory);
        if (!existed) { PrivateFiles.protect(directory); }
        Path lockPath = directory.resolve("session.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            PrivateFiles.protect(lockPath);
            try (FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ChatGPT sign-in was interrupted.");
        } catch (Exception e) {
            throw new IOException("ChatGPT connection could not be completed.");
        }
    }

    private JsonObject read(Path path) throws IOException {
        return Files.exists(path) ? LlmHttp.object(Files.readString(path)) : new JsonObject();
    }

    private JsonObject settings() throws IOException { return read(directory.resolve("settings.json")); }

    private void saveSettings(JsonObject settings) throws IOException {
        PrivateFiles.write(directory.resolve("settings.json"), settings.toString());
    }

    private Path profilePath(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9-]{36}") || !UUID.fromString(id).toString().equals(id)) {
            throw new IOException("Invalid local ChatGPT profile ID.");
        }
        return directory.resolve("profiles").resolve(id + ".json");
    }

    private String selectedProfile(String requested, JsonObject settings) throws IOException {
        if (requested != null) { profilePath(requested); return requested; }
        if (!settings.has("activeProfile")) {
            throw new IOException("Continue with ChatGPT first using the login command.");
        }
        return settings.get("activeProfile").getAsString();
    }

    public JsonArray profiles() throws IOException {
        return locked(() -> {
            JsonArray result = new JsonArray();
            JsonObject settings = settings();
            Path profiles = directory.resolve("profiles");
            if (Files.exists(profiles)) {
                try (var paths = Files.list(profiles)) {
                    for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        JsonObject saved = read(path);
                        JsonObject item = new JsonObject();
                        item.add("id", saved.get("profileId"));
                        item.add("email", saved.get("email"));
                        item.addProperty("active", settings.has("activeProfile")
                                && settings.get("activeProfile").equals(saved.get("profileId")));
                        item.addProperty("usingChatGptPlan", saved.has("access_token") && hasDirectScope(saved));
                        result.add(item);
                    }
                }
            }
            return result;
        });
    }

    public String login(String requested, boolean newAccount) throws IOException {
        return locked(() -> {
            JsonObject settings = settings();
            JsonObject host = read(directory.resolve("host.json"));
            if (!host.has("hostId")) {
                host.addProperty("hostId", "urn:uuid:" + UUID.randomUUID());
                PrivateFiles.write(directory.resolve("host.json"), host.toString());
            }
            String profileId;
            if (newAccount) {
                profileId = UUID.randomUUID().toString();
            } else if (requested != null || settings.has("activeProfile")) {
                profileId = selectedProfile(requested, settings);
            } else if (settings.has("pendingProfile")) {
                profileId = settings.get("pendingProfile").getAsString();
            } else {
                profileId = UUID.randomUUID().toString();
            }
            Path profilePath = profilePath(profileId);
            if (requested != null && !Files.exists(profilePath)) {
                throw new IOException("No saved ChatGPT profile has this ID.");
            }
            JsonObject profile = read(profilePath);
            profile.addProperty("profileId", profileId);
            String registered = profile.has("client_id") ? profile.get("client_id").getAsString() : null;
            String state = randomValue();
            String nonce = randomValue();
            String verifier = randomValue();
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            CompletableFuture<Map<String, String>> callback = new CompletableFuture<>();
            HttpServer listener = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            String redirect = "http://127.0.0.1:" + listener.getAddress().getPort() + "/auth/callback";
            listener.createContext("/auth/callback", exchange -> {
                int status = 200;
                String message = "Authorization received. Return to Forge for the connection result.";
                try {
                    if (!"GET".equals(exchange.getRequestMethod())
                            || !"/auth/callback".equals(exchange.getRequestURI().getPath())) {
                        throw new IOException("Invalid callback.");
                    }
                    Map<String, String> params = callbackParameters(exchange.getRequestURI().getRawQuery(), state, registered);
                    if (params.containsKey("error")) {
                        callback.completeExceptionally(new IOException("ChatGPT authorization was declined."));
                        message = "Authorization was declined. Return to Forge.";
                    } else {
                        callback.complete(params);
                    }
                } catch (IOException e) {
                    status = 400;
                    message = "Invalid or expired authorization callback.";
                }
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            listener.start();
            try {
                Map<String, String> query = new LinkedHashMap<>();
                query.put("client_id", registered == null ? "dynamic_agent_client" : registered);
                if (registered == null) { query.put("agent_name_hint", "Forge LLM Advisor"); }
                query.put("ext_agent_host_id", host.get("hostId").getAsString());
                query.put("response_type", "code");
                query.put("redirect_uri", redirect);
                query.put("scope", "openid profile email offline_access resource.invoke " + DIRECT_SCOPE);
                query.put("resource", RESOURCE);
                query.put("state", state);
                query.put("nonce", nonce);
                query.put("code_challenge_method", "S256");
                query.put("code_challenge", challenge);
                URI authorize = URI.create(ISSUER + "/api/accounts/authorize?" + LlmHttp.form(query));
                System.out.println("Continue with ChatGPT in your browser. Approve ChatGPT plan usage for Forge LLM Advisor.");
                try {
                    Desktop.getDesktop().browse(authorize);
                } catch (Exception e) {
                    // No retained ID token is included in this URL, so it is safe to display locally.
                    System.out.println("Open this URL on this computer: " + authorize);
                }
                Map<String, String> result;
                try {
                    result = callback.get(5, TimeUnit.MINUTES);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IOException("ChatGPT sign-in timed out; start login again.");
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IOException("ChatGPT authorization was declined.");
                }
                String clientId = result.getOrDefault("client_id", registered);
                profile.addProperty("client_id", clientId);
                // Keep the issued registration even if the code expires before exchange.
                PrivateFiles.write(profilePath, profile.toString());
                settings.addProperty("pendingProfile", profileId);
                saveSettings(settings);
                JsonObject token = tokenRequest(Map.of("grant_type", "authorization_code", "client_id", clientId,
                        "code", result.get("code"), "code_verifier", verifier,
                        "redirect_uri", redirect, "resource", RESOURCE));
                JsonObject identity = verifyIdentity(token.get("id_token").getAsString(), clientId, nonce, jwks(), Instant.now());
                checkSameIdentity(profile, identity);
                profile.addProperty("issuer", ISSUER);
                profile.add("subject", identity.get("sub"));
                profile.add("email", identity.get("email"));
                applyTokens(profile, token, false);
                PrivateFiles.write(profilePath, profile.toString());
                settings.addProperty("activeProfile", profileId);
                settings.remove("pendingProfile");
                saveSettings(settings);
                if (!hasDirectScope(profile)) {
                    throw new IOException("Signed in, but ChatGPT plan usage was not granted. Enable it when reconnecting.");
                }
                return profileId;
            } finally {
                listener.stop(0);
            }
        });
    }

    public String accessToken(String requested) throws IOException {
        return locked(() -> {
            String id = selectedProfile(requested, settings());
            Path path = profilePath(id);
            JsonObject profile = read(path);
            if (!profile.has("access_token") || !hasDirectScope(profile)) {
                throw new IOException("Connect this ChatGPT profile and enable ChatGPT plan usage first.");
            }
            long now = Instant.now().getEpochSecond();
            long expires = profile.get("expires_at").getAsLong();
            long earliest = profile.has("earliest_refresh_at") ? profile.get("earliest_refresh_at").getAsLong() : 0;
            if (expires <= now + 60 && earliest <= now) {
                JsonObject token = tokenRequest(Map.of("grant_type", "refresh_token",
                        "client_id", profile.get("client_id").getAsString(),
                        "refresh_token", profile.get("refresh_token").getAsString(), "resource", RESOURCE));
                if (token.has("id_token")) {
                    JsonObject identity = verifyIdentity(token.get("id_token").getAsString(),
                            profile.get("client_id").getAsString(), null, jwks(), Instant.now());
                    checkSameIdentity(profile, identity);
                }
                applyTokens(profile, token, true);
                PrivateFiles.write(path, profile.toString());
                if (!hasDirectScope(profile)) {
                    throw new IOException("ChatGPT plan usage is no longer authorized for this profile.");
                }
            } else if (expires <= now) {
                throw new IOException("ChatGPT access expired before it could be renewed. Reconnect this profile.");
            }
            return profile.get("access_token").getAsString();
        });
    }

    public boolean logout(String requested) throws IOException {
        return locked(() -> {
            Path path = profilePath(selectedProfile(requested, settings()));
            if (!Files.exists(path)) { throw new IOException("No saved ChatGPT profile has this ID."); }
            JsonObject profile = read(path);
            boolean revoked = true;
            if (profile.has("refresh_token")) {
                try {
                    JsonObject discovery = discovery();
                    URI revocation = trustedAuthUri(discovery.get("revocation_endpoint").getAsString());
                    LlmHttp.request(revocation, "POST", LlmHttp.form(Map.of(
                            "token", profile.get("refresh_token").getAsString(),
                            "token_type_hint", "refresh_token", "client_id", profile.get("client_id").getAsString())),
                            "application/x-www-form-urlencoded", null, HTTP_TIMEOUT);
                } catch (Exception e) { revoked = false; }
            }
            for (String field : List.of("access_token", "refresh_token", "id_token", "scope", "expires_at", "earliest_refresh_at")) {
                profile.remove(field);
            }
            PrivateFiles.write(path, profile.toString());
            return revoked;
        });
    }

    static Map<String, String> callbackParameters(String query, String expectedState, String registered) throws IOException {
        if (query == null || query.length() > 16_384) { throw new IOException("Invalid OAuth callback."); }
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (fields.putIfAbsent(key, value) != null) { throw new IOException("Duplicate callback parameter."); }
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid callback encoding."); }
        if (!MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8),
                fields.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("OAuth state mismatch.");
        }
        if (fields.containsKey("error")) { return fields; }
        String issued = fields.getOrDefault("client_id", registered);
        if (issued == null || "dynamic_agent_client".equals(issued) || !issued.matches("[a-zA-Z0-9_-]{1,200}")
                || (registered != null && !registered.equals(issued))
                || !fields.containsKey("code") || fields.get("code").isBlank()) {
            throw new IOException("Incomplete or mismatched client registration.");
        }
        return fields;
    }

    private static String randomValue() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static JsonObject tokenRequest(Map<String, String> fields) throws IOException {
        return LlmHttp.object(LlmHttp.request(TOKEN_ENDPOINT, "POST", LlmHttp.form(fields),
                "application/x-www-form-urlencoded", null, HTTP_TIMEOUT));
    }

    private static JsonObject discovery() throws IOException {
        return LlmHttp.object(LlmHttp.request(URI.create(ISSUER + "/.well-known/openid-configuration"),
                "GET", null, null, null, HTTP_TIMEOUT));
    }

    private static URI trustedAuthUri(String value) throws IOException {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("Untrusted OpenAI authentication endpoint.");
        }
        return uri;
    }

    private static JsonObject jwks() throws IOException {
        URI uri = trustedAuthUri(discovery().get("jwks_uri").getAsString());
        return LlmHttp.object(LlmHttp.request(uri, "GET", null, null, null, HTTP_TIMEOUT));
    }

    static JsonObject verifyIdentity(String jwt, String clientId, String nonce, JsonObject jwks, Instant now) throws IOException {
        try {
            if (jwt.length() > 32_768) { throw new IOException("Invalid ID token."); }
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) { throw new IOException("Invalid ID token."); }
            JsonObject header = LlmHttp.object(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (!"RS256".equals(header.get("alg").getAsString())) { throw new IOException("Unsupported ID-token signature."); }
            JsonObject key = null;
            for (JsonElement element : jwks.getAsJsonArray("keys")) {
                JsonObject candidate = element.getAsJsonObject();
                if ("RSA".equals(candidate.get("kty").getAsString())
                        && header.get("kid").equals(candidate.get("kid"))
                        && (!candidate.has("use") || "sig".equals(candidate.get("use").getAsString()))
                        && (!candidate.has("alg") || "RS256".equals(candidate.get("alg").getAsString()))) {
                    key = candidate;
                    break;
                }
            }
            if (key == null) { throw new IOException("Unknown ID-token signing key."); }
            BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").getAsString()));
            BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").getAsString()));
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) { throw new IOException("Invalid ID-token signature."); }
            JsonObject claims = LlmHttp.object(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            boolean audience = false;
            JsonElement aud = claims.get("aud");
            if (aud.isJsonArray()) {
                for (JsonElement item : aud.getAsJsonArray()) { audience |= clientId.equals(item.getAsString()); }
                if (aud.getAsJsonArray().size() > 1 && (!claims.has("azp")
                        || !clientId.equals(claims.get("azp").getAsString()))) {
                    audience = false;
                }
            } else { audience = clientId.equals(aud.getAsString()); }
            if (!ISSUER.equals(claims.get("iss").getAsString()) || !audience
                    || (claims.has("azp") && !clientId.equals(claims.get("azp").getAsString()))
                    || claims.get("exp").getAsLong() <= now.getEpochSecond()
                    || (claims.has("nbf") && claims.get("nbf").getAsLong() > now.getEpochSecond() + 30)
                    || (nonce != null && (!claims.has("nonce") || !nonce.equals(claims.get("nonce").getAsString())))
                    || claims.get("sub").getAsString().isBlank()) {
                throw new IOException("ID-token identity validation failed.");
            }
            return claims;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("ID-token validation failed."); }
    }

    private static void checkSameIdentity(JsonObject profile, JsonObject identity) throws IOException {
        if (profile.has("subject") && (!profile.get("subject").equals(identity.get("sub"))
                || !ISSUER.equals(profile.get("issuer").getAsString()))) {
            throw new IOException("This connection belongs to another account. Use a new account profile.");
        }
    }

    private static boolean hasDirectScope(JsonObject profile) {
        return profile.has("scope") && List.of(profile.get("scope").getAsString().split("\\s+")).contains(DIRECT_SCOPE);
    }

    private static void applyTokens(JsonObject profile, JsonObject token, boolean refresh) throws IOException {
        if (!token.has("access_token") || !token.has("refresh_token") || !token.has("expires_in")
                || !token.has("token_type") || !"Bearer".equalsIgnoreCase(token.get("token_type").getAsString())
                || (!refresh && !token.has("id_token"))) {
            throw new IOException("Incomplete ChatGPT credentials.");
        }
        if (token.get("access_token").getAsString().isBlank() || token.get("refresh_token").getAsString().isBlank()) {
            throw new IOException("Empty ChatGPT credentials.");
        }
        long lifetime = token.get("expires_in").getAsLong();
        if (lifetime <= 0 || lifetime > 86_400) { throw new IOException("Invalid ChatGPT credential lifetime."); }
        for (String field : List.of("access_token", "refresh_token", "id_token", "scope")) {
            if (token.has(field)) { profile.add(field, token.get(field)); }
        }
        profile.addProperty("expires_at", Instant.now().getEpochSecond() + lifetime);
        if (token.has("earliest_refresh_at")) {
            profile.add("earliest_refresh_at", token.get("earliest_refresh_at"));
        } else { profile.remove("earliest_refresh_at"); }
    }
}
