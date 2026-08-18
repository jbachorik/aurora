package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuitExtensionTest {

    @Test
    @DisplayName("the bundled extension is written out whole, beside the profile")
    void writesEveryFile(@TempDir Path temp) throws Exception {
        Path directory = QuitExtension.ensureInstalled(temp).orElseThrow();

        assertEquals(temp.resolve("browser-extension"), directory);
        for (String file : QuitExtension.FILES) {
            assertTrue(Files.size(directory.resolve(file)) > 0, file + " is missing or empty");
        }
        // The manifest is the extension's front door; a malformed copy would
        // make Chromium refuse the whole directory silently.
        String manifest = Files.readString(directory.resolve("manifest.json"));
        assertTrue(manifest.contains("\"manifest_version\": 3"), manifest);
        assertTrue(manifest.contains("quit.js"), manifest);
    }

    @Test
    @DisplayName("writing over an existing copy refreshes it rather than failing")
    void overwritesAStaleCopy(@TempDir Path temp) throws Exception {
        Path stale = temp.resolve("browser-extension").resolve("quit.js");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "stale");

        QuitExtension.ensureInstalled(temp).orElseThrow();

        assertTrue(Files.readString(stale).contains("keydown"));
    }
}
