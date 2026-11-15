package forge.llm;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Invented reference text, not a copy of Wizards' rulebook. */
final class RulesFixtures {
    static URI source(String edition) {
        return URI.create("https://media.wizards.com/" + edition.substring(0, 4)
                + "/downloads/MagicCompRules%20" + edition.replace("-", "") + ".txt");
    }

    static String document(String edition) {
        String date = LocalDate.parse(edition).format(DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH));
        StringBuilder text = new StringBuilder("Magic: The Gathering Comprehensive Rules\nThese rules are effective as of ")
                .append(date).append(".\n\n100. General\n100.1. Synthetic introduction.\n")
                .append("101.2. Synthetic precedence fixture.\nExample: A synthetic example.\n101.2a A synthetic exception.\n")
                .append("110.1. Synthetic permanent on the battlefield fixture.\n")
                .append("302.6. Synthetic summoning sickness fixture.\n")
                .append("702.9. Synthetic Flying heading\n702.9a Synthetic flying definition.\n702.9b Synthetic flying restriction.\n");
        for (int i = 1000; i < 2000; i++) {
            text.append("702.").append(i).append(". Invented fixture text for testing bounded excerpts and indexing.\n");
        }
        return text.append("903.1. Synthetic variant fixture.\n\nGlossary\n\nFlying\nSynthetic glossary fixture.\n\nCredits\nSynthetic test authors.\n").toString();
    }

    static RulesReference reference() {
        String edition = "2026-11-06";
        return new RulesReference(document(edition), edition, source(edition).toString());
    }
}
