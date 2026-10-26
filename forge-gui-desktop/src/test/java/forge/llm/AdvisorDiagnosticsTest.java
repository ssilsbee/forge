package forge.llm;

import org.testng.annotations.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.testng.Assert.*;

public class AdvisorDiagnosticsTest {
    @Test public void concurrentEventsAreCountedAndTimingSummarizesActualSamples() {
        AdvisorDiagnostics diagnostics = new AdvisorDiagnostics();
        List<CompletableFuture<Void>> writers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            writers.add(CompletableFuture.runAsync(() -> {
                for (int n = 0; n < 100; n++) { diagnostics.count("Engine results", "Played"); }
            }));
        }
        writers.forEach(CompletableFuture::join);
        diagnostics.time("Model reply", 10); diagnostics.time("Model reply", 30);
        String report = diagnostics.render();
        assertTrue(report.contains("Played: 400"));
        assertTrue(report.contains("2 samples, average 20, longest 30, total 40"));
        assertTrue(report.contains("not every decision in Forge"));
    }

    @Test public void unexpectedCategoriesCannotGrowSessionMemoryWithoutBound() {
        AdvisorDiagnostics diagnostics = new AdvisorDiagnostics();
        for (int i = 0; i < 10_000; i++) {
            diagnostics.count("Category " + i, "Reason " + i);
            diagnostics.time("Timing " + i, 1);
        }
        assertTrue(diagnostics.render().length() < 40_000);
        assertTrue(diagnostics.render().contains("Additional categories: 9872"));
    }
}
