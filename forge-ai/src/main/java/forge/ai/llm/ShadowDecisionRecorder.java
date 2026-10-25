package forge.ai.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in local recording only. There is no network client or LLM dependency on the game thread. */
public final class ShadowDecisionRecorder {
    public static final String DIRECTORY_PROPERTY = "forge.llm.captureDir";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicInteger RECORDED = new AtomicInteger();
    private static final AtomicInteger DROPPED = new AtomicInteger();

    private ShadowDecisionRecorder() { }

    private static final class Writer {
        private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), job -> {
                    Thread thread = new Thread(job, "forge-llm-capture");
                    thread.setDaemon(true);
                    return thread;
                });
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                EXECUTOR.shutdown();
                try {
                    EXECUTOR.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "forge-llm-capture-flush"));
        }
    }

    public static void record(Player player, List<SpellAbility> chosen) {
        String directory = System.getProperty(DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank() || chosen == null || chosen.size() != 1
                || !chosen.get(0).isLandAbility() || player.getGame().getMaingame() != null
                || player.getGame().isNoGUIUser()
                || RECORDED.get() >= Integer.getInteger("forge.llm.captureLimit", 100)) {
            return;
        }
        try {
            JsonObject snapshot = LandDecisionSnapshot.capture(player, chosen.get(0).getHostCard());
            if (snapshot == null) {
                return;
            }
            String content = JSON.toJson(snapshot);
            if (content.length() > 512_000) {
                return;
            }
            Path destination = Path.of(directory).resolve("land-"
                    + snapshot.get("decisionId").getAsString() + ".json");
            // Only detached text crosses threads. Never keep a live Game/Card reference in a task.
            Writer.EXECUTOR.execute(() -> {
                try {
                    PrivateFiles.write(destination, content);
                } catch (Exception e) {
                    noteFailure(e);
                }
            });
            RECORDED.incrementAndGet();
        } catch (Exception e) {
            // Shadow observation must never turn a valid AI choice into a failed move.
            noteFailure(e);
        }
    }

    private static void noteFailure(Exception e) {
        if (DROPPED.incrementAndGet() == 1) {
            System.err.println("LLM shadow capture skipped a snapshot (" + e.getClass().getSimpleName() + ").");
        }
    }
}
