package forge.app.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Defers the OAuth exchange until the user returns from the browser to Forge. */
final class SignInForegroundGate implements AutoCloseable {
    private boolean foreground;
    private boolean closed;

    synchronized void setForeground(boolean value) {
        foreground = value;
        notifyAll();
    }

    synchronized void awaitForeground(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!foreground && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("Return to Forge sooner to finish connecting. Start ChatGPT sign-in again.");
            }
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("ChatGPT connection was cancelled.", e);
            }
        }
        if (closed) { throw new IOException("Forge closed before ChatGPT connection finished."); }
    }

    @Override public synchronized void close() {
        closed = true;
        notifyAll();
    }
}
