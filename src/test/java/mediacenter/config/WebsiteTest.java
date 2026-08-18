package mediacenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WebsiteTest {

    @Test
    @DisplayName("a bare host gets https, because nobody types a scheme from a sofa")
    void defaultsTheScheme() {
        assertEquals("https://cinema.mosfilm.ru",
                Website.create("Mosfilm", "cinema.mosfilm.ru").url());
    }

    @Test
    @DisplayName("an address that names its scheme is left exactly alone")
    void keepsAnExplicitScheme() {
        assertEquals("http://player.local:8096",
                Website.create("Jellyfin", "http://player.local:8096").url());
    }

    @Test
    @DisplayName("name and address are trimmed, never rewritten")
    void trimsWhatItStores() {
        Website website = Website.create("  Mosfilm  ", "  cinema.mosfilm.ru  ");

        assertEquals("Mosfilm", website.name());
        assertEquals("https://cinema.mosfilm.ru", website.url());
    }

    @Test
    @DisplayName("every tile gets an identity of its own")
    void generatesDistinctIds() {
        assertNotEquals(
                Website.create("A", "a.example").id(),
                Website.create("A", "a.example").id());
    }

    @Test
    @DisplayName("the host is the address without its scheme and path")
    void extractsTheHost() {
        assertEquals("cinema.mosfilm.ru",
                Website.create("Mosfilm", "https://cinema.mosfilm.ru/films/").host());
        assertEquals("player.local:8096",
                Website.create("Jellyfin", "http://player.local:8096/web").host());
    }

    @Test
    @DisplayName("a blank name or address is refused outright")
    void refusesBlanks() {
        assertThrows(IllegalArgumentException.class, () -> new Website("id", " ", "https://a"));
        assertThrows(IllegalArgumentException.class, () -> new Website("id", "A", " "));
        assertThrows(IllegalArgumentException.class, () -> new Website(" ", "A", "https://a"));
    }
}
