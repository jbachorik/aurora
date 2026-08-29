package mediacenter;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplicationVersionTest {

    @Test
    @DisplayName("the version resource generated at build time is read back, not left blank")
    void readsTheGeneratedVersion() {
        assertFalse(ApplicationVersion.current().isBlank());
    }
}
