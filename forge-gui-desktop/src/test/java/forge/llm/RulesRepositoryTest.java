package forge.llm;

import com.google.gson.JsonObject;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.testng.Assert.*;

public class RulesRepositoryTest {
    @Test public void replacingAUiSubscriptionReleasesTheOldCallback() throws Exception {
        RulesRepository repository = repo(Files.createTempDirectory("rules-subscriptions"), NOW,
                online(OLD, new AtomicInteger()));
        List<String> first = new ArrayList<>(), replacement = new ArrayList<>();
        RulesRepository.Subscription old = repository.subscribe(first::add);
        assertEquals(first.size(), 1, "Subscribers immediately receive the current status");
        old.close(); old.close();
        try (RulesRepository.Subscription next = repository.subscribe(replacement::add);
             RulesRepository.Subscription broken = repository.subscribe(message -> { throw new IllegalStateException(); })) {
            repository.refresh();
            assertEquals(first.size(), 1, "Closed UI must never receive another notification");
            assertTrue(replacement.get(replacement.size() - 1).contains("Rules reference ready"));
        }
        int delivered = replacement.size();
        repository.refresh();
        assertEquals(replacement.size(), delivered);
    }
    private static final String OLD = "2026-11-06";
    private static final String NEW = "2026-11-13";
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");

    private RulesRepository repo(Path directory, Instant now, RulesRepository.Download download) {
        return new RulesRepository(directory, download, Clock.fixed(now, ZoneOffset.UTC), message -> { });
    }

    private RulesRepository.Download online(String edition, AtomicInteger requests) {
        return uri -> {
            requests.incrementAndGet();
            return uri.equals(RulesRepository.PAGE) ? "<a href=\"" + RulesFixtures.source(edition) + "\">TXT</a>"
                    : RulesFixtures.document(edition);
        };
    }

    @Test public void initialDownloadIsValidatedAndReusedOfflineAcrossLaunches() throws Exception {
        Path directory = Files.createTempDirectory("rules-cache-test"); AtomicInteger requests = new AtomicInteger();
        RulesRepository repository = repo(directory, NOW, online(OLD, requests));
        assertNull(repository.current());
        repository.refresh();
        assertEquals(repository.current().edition(), OLD);
        assertEquals(requests.get(), 2);
        assertTrue(Files.isRegularFile(directory.resolve("rules-cache.json")));
        repository.refresh(); assertEquals(requests.get(), 2, "At most one update check daily");
        RulesRepository offline = repo(directory, NOW.plusSeconds(30), uri -> { throw new IOException("offline"); });
        assertEquals(offline.current().edition(), OLD);
        offline.refresh(); assertEquals(offline.current().edition(), OLD);
    }

    @Test public void newEditionDownloadsButUnchangedEditionDoesNot() throws Exception {
        Path directory = Files.createTempDirectory("rules-update-test");
        RulesRepository first = repo(directory, NOW, online(OLD, new AtomicInteger())); first.refresh();
        AtomicInteger checked = new AtomicInteger();
        RulesRepository same = repo(directory, NOW.plusSeconds(86401), online(OLD, checked)); same.refresh();
        assertEquals(checked.get(), 1, "Unchanged version only fetches the rules page");
        AtomicInteger updated = new AtomicInteger();
        RulesRepository newer = repo(directory, NOW.plusSeconds(172802), online(NEW, updated));
        assertEquals(newer.current().edition(), OLD);
        newer.refresh(); assertEquals(newer.current().edition(), NEW); assertEquals(updated.get(), 2);
        assertTrue(new String(Files.readAllBytes(directory.resolve("rules-cache.json")), java.nio.charset.StandardCharsets.UTF_8).contains(NEW));
    }

    @Test public void failedOrMalformedUpdatesPreserveTheLastGoodDocument() throws Exception {
        for (int failure = 0; failure < 3; failure++) {
            Path directory = Files.createTempDirectory("rules-failed-update");
            repo(directory, NOW, online(OLD, new AtomicInteger())).refresh();
            int variant = failure;
            RulesRepository repository = repo(directory, NOW.plusSeconds(86401), uri -> {
                if (variant == 0) { throw new IOException("offline"); }
                if (uri.equals(RulesRepository.PAGE)) { return RulesFixtures.source(NEW).toString(); }
                return variant == 1 ? "<html>temporary error</html>" : RulesFixtures.document(OLD);
            });
            repository.refresh();
            assertEquals(repository.current().edition(), OLD);
            assertTrue(repository.status().contains("using cached"));
            RulesRepository restarted = repo(directory, NOW.plusSeconds(86402), uri -> { throw new IOException("offline"); });
            assertEquals(restarted.current().edition(), OLD);
            JsonObject record = LlmHttp.object(Files.readString(directory.resolve("rules-cache.json")));
            assertEquals(record.get("text").getAsString(), RulesFixtures.document(OLD));
        }
    }

    @Test public void absentOrCorruptCacheAndFailedDownloadNeverExposeAReference() throws Exception {
        for (boolean corrupt : new boolean[]{false, true}) {
            Path directory = Files.createTempDirectory("rules-unavailable");
            if (corrupt) { Files.writeString(directory.resolve("rules-cache.json"), "corrupt"); }
            List<String> messages = new ArrayList<>();
            RulesRepository repository = new RulesRepository(directory, uri -> { throw new IOException("offline"); },
                    Clock.fixed(NOW, ZoneOffset.UTC), messages::add);
            assertNull(repository.current()); repository.refresh(); assertNull(repository.current());
            assertTrue(messages.get(messages.size() - 1).contains("tools are disabled"));
        }
    }

    @Test public void officialDatedLinksAndEffectiveDatesAreRequired() throws Exception {
        URI latest = RulesRepository.latestSource("<a href='" + RulesFixtures.source(OLD) + "'>TXT</a> "
                + "{\"url\":\"" + RulesFixtures.source(NEW).toString().replace("/", "\\/") + "\"}");
        assertEquals(latest, RulesFixtures.source(NEW));
        assertThrows(IOException.class, () -> RulesRepository.latestSource("https://example.com/MagicCompRules20261113.txt"));
        assertThrows(IOException.class, () -> RulesRepository.latestSource("https://media.wizards.com/2026/downloads/MagicCompRules20269999.txt"));
        assertThrows(IOException.class, () -> RulesRepository.validate(RulesFixtures.document(OLD), RulesFixtures.source(NEW)));
        assertThrows(IOException.class, () -> RulesRepository.validate("100.1. truncated", RulesFixtures.source(OLD)));
        assertThrows(IOException.class, () -> RulesRepository.validate(RulesFixtures.document(OLD), URI.create("http://media.wizards.com/2026/downloads/MagicCompRules20261106.txt")));
    }
}
