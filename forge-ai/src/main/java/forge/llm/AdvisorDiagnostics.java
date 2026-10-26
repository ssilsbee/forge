package forge.llm;

import java.util.Map;
import java.util.TreeMap;

/** Bounded session aggregates. Contains no hands, card names, prompts or credentials. */
public final class AdvisorDiagnostics {
    private static final int MAX_LABELS = 128;
    private final Map<String, Map<String, Long>> counts = new TreeMap<>();
    private final Map<String, Timing> timings = new TreeMap<>();
    private int labels;
    private static final class Timing { long count, total, longest; }

    public synchronized void count(String category, String detail) {
        Map<String, Long> group = counts.get(category);
        if (group == null || !group.containsKey(detail)) {
            if (labels >= MAX_LABELS) { category = "Other observations"; detail = "Additional categories"; }
            else { labels++; }
        }
        counts.computeIfAbsent(category, key -> new TreeMap<>()).merge(detail, 1L, Long::sum);
    }

    public synchronized void time(String category, long millis) {
        if (!timings.containsKey(category) && timings.size() >= MAX_LABELS) { category = "Other timing"; }
        Timing timing = timings.computeIfAbsent(category, key -> new Timing());
        timing.count++; timing.total += Math.max(0, millis); timing.longest = Math.max(timing.longest, millis);
    }

    public synchronized String render() {
        StringBuilder text = new StringBuilder("CURRENT ADVISOR SESSION\n\n");
        text.append("Counts describe observed advisor hooks, not every decision in Forge.\n")
                .append("An omitted alternative may still be chosen through Forge's prepared move.\n")
                .append("Repeated evaluations of an ability are counted again.\n")
                .append("Validated selections are not proof of engine execution.\n\n");
        if (counts.isEmpty() && timings.isEmpty()) { text.append("No gameplay observations yet.\n"); }
        for (var group : counts.entrySet()) {
            text.append(group.getKey()).append('\n');
            group.getValue().forEach((detail, count) -> text.append("  ").append(detail).append(": ").append(count).append('\n'));
            text.append('\n');
        }
        if (!timings.isEmpty()) {
            text.append("TIMING (milliseconds; average rounded down)\n");
            timings.forEach((name, t) -> text.append("  ").append(name).append(": ")
                    .append(t.count).append(" samples, average ").append(t.total / t.count)
                    .append(", longest ").append(t.longest).append(", total ").append(t.total).append('\n'));
            text.append("\nGame wait includes queue time, unfinished deck planning, model replies and report recording.\n")
                    .append("Timing rows overlap; do not add them together.\n");
        }
        return text.toString();
    }
}
