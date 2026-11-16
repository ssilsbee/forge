package forge.llm;

import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class DesktopAdvisorSettingsTest {
    @Test public void settingsRoundTrip() {
        DesktopAdvisorSettings settings = new DesktopAdvisorSettings("  example-model  ", "medium", 120);
        assertEquals(settings.model(), "example-model");
        assertEquals(DesktopAdvisorSettings.parse(settings.json()), settings);
    }

    @Test public void rejectsInvalidSavedSettings() {
        for (String json : new String[]{
                "{}", "null", "{\"model\":\"\",\"effort\":\"high\",\"timeout\":60}",
                "{\"model\":\"example\",\"effort\":\"invalid\",\"timeout\":60}",
                "{\"model\":\"example\",\"effort\":\"high\",\"timeout\":0}",
                "{\"model\":\"example\",\"effort\":\"high\",\"timeout\":301}",
                "{\"model\":\"example\",\"effort\":\"high\",\"timeout\":1.5}"}) {
            assertThrows(RuntimeException.class, () -> DesktopAdvisorSettings.parse(json));
        }
    }

    @Test public void checkoutReusesExistingConnections() throws Exception {
        Path root = Files.createTempDirectory("advisor-checkout");
        try {
            Files.createDirectory(root.resolve("tools"));
            Files.createFile(root.resolve("tools/llm-advisor.ps1"));
            Path working = root.resolve("forge-gui-desktop/target");
            assertEquals(DesktopAdvisor.dataDirectory(working, root.resolve("user"), null), root.resolve(".forge-llm"));
            assertEquals(DesktopAdvisor.dataDirectory(working, root.resolve("user"), root.resolve("override").toString()), root.resolve("override"));
        } finally {
            Files.deleteIfExists(root.resolve("tools/llm-advisor.ps1"));
            Files.deleteIfExists(root.resolve("tools"));
            Files.deleteIfExists(root);
        }
    }

    @Test public void installedAppStoresSettingsInUserDirectory() throws Exception {
        Path root = Files.createTempDirectory("advisor-installed");
        try {
            assertEquals(DesktopAdvisor.dataDirectory(root.resolve("app"), root.resolve("user"), null), root.resolve("user/.forge-llm"));
        } finally { Files.deleteIfExists(root); }
    }
}
