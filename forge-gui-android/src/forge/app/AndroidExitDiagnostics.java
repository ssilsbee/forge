package forge.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import forge.ai.llm.PrivateFiles;
import forge.util.LogExporter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.time.Instant;
import java.util.Base64;

/** Collects this app's system exit history, including native crashes absent from forge.log. */
final class AndroidExitDiagnostics {
    private static final int MAX_TRACE_BYTES = 2_000_000;

    private AndroidExitDiagnostics() { }

    static void start(Context application) {
        Thread worker = new Thread(() -> {
            try {
                File report = new File(application.getNoBackupFilesDir(), "diagnostics/forge.android-exits.log");
                StringBuilder text = new StringBuilder("Forge Android process exit report\n");
                text.append("Collected: ").append(Instant.now()).append('\n');
                text.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
                text.append("Android: ").append(Build.VERSION.RELEASE).append("; SDK ").append(Build.VERSION.SDK_INT).append('\n');
                text.append("Current max Java heap bytes: ").append(Runtime.getRuntime().maxMemory()).append('\n');
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    collect(application, text);
                } else { text.append("System exit history requires Android 11 or newer.\n"); }
                PrivateFiles.write(report.toPath(), text.toString());
                LogExporter.registerDiagnosticFile(report);
            } catch (Exception | LinkageError failure) {
                android.util.Log.w("ForgeDiagnostics", "Exit history could not be saved ("
                        + failure.getClass().getSimpleName() + ").");
            }
        }, "forge-exit-diagnostics");
        worker.setDaemon(true);
        worker.start();
    }

    private static void collect(Context application, StringBuilder text) {
        try {
            ActivityManager manager = (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
            int traces = 0;
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(application.getPackageName(), 0, 20)) {
                text.append("\nExit at ").append(Instant.ofEpochMilli(exit.getTimestamp())).append('\n');
                text.append("Process: ").append(exit.getProcessName()).append("; pid ").append(exit.getPid()).append('\n');
                text.append("Reason: ").append(reason(exit.getReason())).append(" (").append(exit.getReason()).append(")\n");
                text.append("Status or signal: ").append(exit.getStatus()).append('\n');
                text.append("Importance: ").append(exit.getImportance()).append('\n');
                text.append("Last sampled memory: PSS ").append(exit.getPss()).append(" KB; RSS ").append(exit.getRss()).append(" KB\n");
                String description = exit.getDescription();
                if (description != null) {
                    text.append("Description: ").append(description.substring(0, Math.min(description.length(), 4000))).append('\n');
                }
                if (traces < 3 && (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                        || exit.getReason() == ApplicationExitInfo.REASON_ANR)) {
                    traces++;
                    appendTrace(exit, text);
                }
            }
        } catch (Exception | LinkageError failure) {
            text.append("System exit history unavailable: ").append(failure.getClass().getSimpleName()).append('\n');
        }
    }

    private static void appendTrace(ApplicationExitInfo exit, StringBuilder text) {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) { text.append("System trace: no longer retained by Android.\n"); return; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_TRACE_BYTES) {
                    text.append("System trace exceeds the 2 MB report limit.\n"); return;
                }
                bytes.write(buffer, 0, count);
            }
            // Native tombstones are protobuf, while ANR traces are text. Preserve both losslessly.
            text.append("System trace format: ").append(exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                    ? "native tombstone protobuf" : "ANR text").append('\n');
            text.append("TRACE_BASE64_BEGIN\n").append(Base64.getEncoder().encodeToString(bytes.toByteArray()))
                    .append("\nTRACE_BASE64_END\n");
        } catch (Exception failure) {
            text.append("System trace unavailable: ").append(failure.getClass().getSimpleName()).append('\n');
        }
    }

    private static String reason(int reason) {
        return switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "App exited itself";
            case ApplicationExitInfo.REASON_SIGNALED -> "Process received a signal";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "Android low memory termination";
            case ApplicationExitInfo.REASON_CRASH -> "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash";
            case ApplicationExitInfo.REASON_ANR -> "App not responding";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permissions changed";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "User or system requested stop";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped app";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency process died";
            case ApplicationExitInfo.REASON_FREEZER -> "Frozen process terminated";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "Package state changed";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "App updated";
            default -> "Other or unknown";
        };
    }
}
