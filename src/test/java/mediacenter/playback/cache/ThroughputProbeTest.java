package mediacenter.playback.cache;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThroughputProbeTest {

    @Test
    @DisplayName("a readable file measures a positive rate")
    void measuresAReadableFile(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("clip.mkv");
        Files.write(file, new byte[4 << 20]);

        OptionalLong rate = ThroughputProbe.measure(file);

        assertTrue(rate.isPresent());
        assertTrue(rate.getAsLong() > 0);
    }

    @Test
    void anUnreadableFileIsEmptyRatherThanAnError(@TempDir Path temp) {
        assertTrue(ThroughputProbe.measure(temp.resolve("gone.mkv")).isEmpty());
    }

    @Test
    void anEmptyFileHasNothingToMeasure(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("empty.mkv");
        Files.createFile(file);

        assertTrue(ThroughputProbe.measure(file).isEmpty());
    }
}
