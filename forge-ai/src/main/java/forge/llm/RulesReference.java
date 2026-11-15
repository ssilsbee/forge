package forge.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline reference only: never runs commands or reads paths supplied by a model. */
final class RulesReference {
    static final int MAX_RESULT_CHARACTERS = 10000;
    private static final Pattern NUMBER = Pattern.compile("^(\\d{3}(?:\\.\\d+[a-z]?)?)\\.?\\s+.*");
    private record Entry(String id, String text) { }
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, JsonObject> cache = new LinkedHashMap<>();
    private final String edition;
    private final String source;

    String edition() { return edition; }
    int size() { return entries.size(); }

    RulesReference(String text, String edition, String source) {
        this.edition = edition;
        this.source = source;
        // Skip the duplicated table of contents; index the actual rules and glossary separately.
        int firstRule = text.indexOf("100.1.");
        if (firstRule < 0) { throw new IllegalArgumentException("Rules document has no numbered rules."); }
        int heading = text.lastIndexOf("100. General", firstRule);
        int start = heading < 0 ? firstRule : heading;
        String id = null;
        StringBuilder block = new StringBuilder();
        boolean glossary = false;
        boolean glossaryTerm = false;
        for (String line : text.substring(start).split("\\r?\\n")) {
            if (line.equals("Glossary")) {
                if (id != null) { entries.add(new Entry(id, block.toString().strip())); }
                id = null; block.setLength(0); glossary = true; glossaryTerm = true;
                continue;
            }
            if (glossary && line.equals("Credits")) { break; }
            var match = NUMBER.matcher(line);
            boolean numbered = !glossary && match.matches();
            boolean term = glossary && glossaryTerm && !line.isBlank();
            if (numbered || term) {
                if (id != null) { entries.add(new Entry(id, block.toString().strip())); }
                id = numbered ? match.group(1) : "glossary:" + line;
                block.setLength(0);
                glossaryTerm = false;
            }
            if (id != null) { block.append(line).append('\n'); }
            if (glossary && line.isBlank()) { glossaryTerm = true; }
        }
        if (id != null && block.length() > 0) { entries.add(new Entry(id, block.toString().strip())); }
    }

    synchronized JsonObject lookup(String name, JsonObject arguments) {
        String field = "search_rules".equals(name) ? "query" : "rule_number";
        if ((!name.equals("search_rules") && !name.equals("read_rules")) || arguments.size() != 1
                || !arguments.has(field) || !arguments.get(field).isJsonPrimitive()
                || !arguments.get(field).getAsJsonPrimitive().isString()) {
            return error("Use search_rules with query, or read_rules with rule_number, as a single string argument.");
        }
        String value = arguments.get(field).getAsString().strip();
        if (value.isBlank() || value.length() > 200) { return error("Lookup must contain 1–200 characters."); }
        String key = name + ":" + value.toLowerCase(Locale.ROOT);
        JsonObject cached = cache.get(key);
        if (cached != null) { return cached.deepCopy(); }
        List<Entry> matches;
        if (name.equals("read_rules")) {
            String number = value.replaceFirst("\\.$", "");
            if (!number.matches("\\d{3}(?:\\.\\d+[a-z]?)?")) { return error("Expected a rule number such as 302.6 or 702.9."); }
            matches = entries.stream().filter(e -> e.id.equals(number) || e.id.startsWith(number + ".")
                    || (number.contains(".") && e.id.matches(Pattern.quote(number) + "[a-z]"))).toList();
        } else {
            String query = value.toLowerCase(Locale.ROOT);
            String[] words = query.split("[^\\p{L}\\p{N}]+");
            matches = entries.stream().filter(e -> score(e, query, words) > 0)
                    .sorted(Comparator.<Entry>comparingInt(e -> score(e, query, words)).reversed())
                    .limit(8).toList();
        }
        JsonObject result = envelope();
        JsonArray excerpts = new JsonArray();
        int remaining = MAX_RESULT_CHARACTERS;
        for (Entry entry : matches) {
            if (remaining <= 0) { break; }
            JsonObject item = new JsonObject(); item.addProperty("rule", entry.id);
            int length = Math.min(remaining, entry.text.length());
            item.addProperty("text", entry.text.substring(0, length));
            item.addProperty("truncated", length < entry.text.length());
            excerpts.add(item); remaining -= length;
        }
        result.add("excerpts", excerpts);
        result.addProperty("truncated", excerpts.size() < matches.size()
                || excerpts.asList().stream().anyMatch(e -> e.getAsJsonObject().get("truncated").getAsBoolean()));
        result.addProperty("hint", excerpts.isEmpty() ? "No match. Try a keyword, fewer words, or a different rule number."
                : "Use read_rules for a specific numbered section or follow cross-references. Card text and actual game state still apply.");
        if (cache.size() >= 64) { cache.remove(cache.keySet().iterator().next()); }
        cache.put(key, result);
        return result.deepCopy();
    }

    private static int score(Entry entry, String query, String[] words) {
        String text = entry.text.toLowerCase(Locale.ROOT);
        boolean phrase = text.contains(query);
        if (!phrase) {
            for (String word : words) { if (!word.isBlank() && !text.contains(word)) { return 0; } }
        }
        String firstLine = text.split("\\n", 2)[0];
        return (phrase ? 20 : 1) + (firstLine.contains(query) ? 30 : 0)
                + (entry.id.startsWith("glossary:") ? 10 : 0);
    }

    JsonObject error(String message) {
        JsonObject result = envelope(); result.addProperty("error", message); return result;
    }

    private JsonObject envelope() {
        JsonObject result = new JsonObject(); result.addProperty("edition", edition);
        result.addProperty("source", source); return result;
    }
}
