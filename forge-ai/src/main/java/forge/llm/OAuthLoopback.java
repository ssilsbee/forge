package forge.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Small loopback-only OAuth callback listener, available on Android and desktop Java. */
final class OAuthLoopback implements AutoCloseable {
    private final ServerSocket server = new ServerSocket();
    private final CompletableFuture<Map<String, String>> result = new CompletableFuture<>();
    private volatile Socket current;

    OAuthLoopback(String state, String registered) throws IOException {
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread thread = new Thread(() -> listen(state, registered), "forge-chatgpt-callback");
        thread.setDaemon(true);
        thread.start();
    }

    String redirect() { return "http://127.0.0.1:" + server.getLocalPort() + "/auth/callback"; }

    Map<String, String> await() throws Exception { return await(java.time.Duration.ofMinutes(5)); }

    Map<String, String> await(java.time.Duration timeout) throws Exception {
        return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void listen(String state, String registered) {
        try {
            while (!server.isClosed() && !result.isDone()) {
                try (Socket socket = server.accept()) {
                    current = socket;
                    socket.setSoTimeout(5000);
                    Map<String, String> params = null;
                    String message = "Invalid or expired authorization callback.";
                    int status = 400;
                    try {
                        ByteArrayOutputStream header = new ByteArrayOutputStream();
                        int ch;
                        while ((ch = socket.getInputStream().read()) != -1) {
                            header.write(ch);
                            if (header.size() > 20_000) { throw new IOException("Callback too large."); }
                            if (header.size() >= 4 && ch == '\n'
                                    && header.toString("US-ASCII").endsWith("\r\n\r\n")) { break; }
                        }
                        String[] request = header.toString("US-ASCII").split("\r\n", 2)[0].split(" ");
                        if (request.length != 3 || !"GET".equals(request[0]) || !request[1].startsWith("/")) {
                            throw new IOException("Invalid callback request.");
                        }
                        URI uri = URI.create(request[1]);
                        if (!"/auth/callback".equals(uri.getPath())) { throw new IOException("Invalid callback path."); }
                        params = ChatGptAuth.callbackParameters(uri.getRawQuery(), state, registered);
                        status = 200;
                        message = params.containsKey("error") ? "Authorization declined. Return to Forge."
                                : "Authorization received. Return to Forge for the connection result.";
                    } catch (Exception ignored) { /* A malformed request does not consume the valid callback. */ }
                    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                    String response = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
                            + "\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\n"
                            + "Connection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(bytes);
                    socket.getOutputStream().flush();
                    if (params != null) {
                        if (params.containsKey("error")) { result.completeExceptionally(new IOException("Authorization declined.")); }
                        else { result.complete(params); }
                    }
                } catch (IOException e) {
                    if (server.isClosed()) { break; }
                } finally { current = null; }
            }
        } finally { result.completeExceptionally(new IOException("Sign-in listener closed.")); }
    }

    @Override public void close() throws IOException {
        server.close();
        Socket socket = current;
        if (socket != null) { socket.close(); }
    }
}
