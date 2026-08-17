package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FileVisibilityTest {

    @Test
    void anOrdinaryFileIsVisible(@TempDir Path temp) throws IOException {
        assertFalse(FileVisibility.isHiddenOrSystem(Files.createFile(temp.resolve("beach.jpg"))));
    }

    @Test
    @DisplayName("an entry that is not there is skipped, exactly as the scanner skips it")
    void anEntryThatIsNotThereIsSkipped(@TempDir Path temp) {
        assertTrue(FileVisibility.isHiddenOrSystem(temp.resolve("gone.jpg")));
    }

    @Test
    @DisplayName("on Windows the hidden attribute is honoured")
    @EnabledOnOs(OS.WINDOWS)
    void honoursTheHiddenAttribute(@TempDir Path temp) throws IOException {
        Path hidden = Files.createFile(temp.resolve("hidden.jpg"));
        Files.setAttribute(hidden, "dos:hidden", true);

        assertTrue(FileVisibility.isHiddenOrSystem(hidden));
    }
}
