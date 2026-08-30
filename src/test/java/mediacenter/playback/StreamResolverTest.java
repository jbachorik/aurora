package mediacenter.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.playback.StreamResolver.ResolvedStream;

class StreamResolverTest {

    @Test
    @DisplayName("the command asks for one address and hands the page URL through verbatim")
    void buildsTheDocumentedCommandLine() {
        List<String> command = StreamResolver.commandFor(
                Path.of("/usr/local/bin/yt-dlp"), "https://cinema.mosfilm.ru/films/film/--dashes");

        assertEquals(List.of(
                "/usr/local/bin/yt-dlp",
                "--dump-json",
                "--no-playlist",
                "--no-warnings",
                "-f", "b",
                "--", "https://cinema.mosfilm.ru/films/film/--dashes"), command);
    }

    @Test
    @DisplayName("yt-dlp's answer yields the address, the title and the headers that matter")
    void parsesTheAnswer() {
        Optional<ResolvedStream> stream = StreamResolver.parse("""
                {
                  "id": "456239817",
                  "title": "Летят журавли",
                  "url": "https://cdn.example.org/film/index.m3u8?sig=abc",
                  "http_headers": {
                    "User-Agent": "Mozilla/5.0",
                    "Referer": "https://vk.com/video_ext.php",
                    "Accept-Language": "en-us,en;q=0.5"
                  },
                  "formats": [{"url": "https://cdn.example.org/other"}]
                }""");

        ResolvedStream resolved = stream.orElseThrow();
        assertEquals("https://cdn.example.org/film/index.m3u8?sig=abc", resolved.url());
        assertEquals(Optional.of("Летят журавли"), resolved.title());
        assertEquals("https://vk.com/video_ext.php", resolved.httpHeaders().get("Referer"));
        assertEquals("Mozilla/5.0", resolved.httpHeaders().get("User-Agent"));
    }

    @Test
    @DisplayName("an answer without a stream address, or that is no JSON, resolves to nothing")
    void refusesUnusableAnswers() {
        assertTrue(StreamResolver.parse("{\"title\": \"a film with no url\"}").isEmpty());
        assertTrue(StreamResolver.parse("{\"url\": \"   \"}").isEmpty());
        assertTrue(StreamResolver.parse("yt-dlp exploded").isEmpty());
        assertTrue(StreamResolver.parse("").isEmpty());
    }

    @Test
    @DisplayName("headers that are not strings are left out rather than mangled")
    void skipsNonStringHeaders() {
        ResolvedStream resolved = StreamResolver.parse(
                "{\"url\": \"https://cdn/f.m3u8\", \"http_headers\": {\"Referer\": \"https://r\", \"X-Odd\": 7}}")
                .orElseThrow();

        assertEquals(Map.of("Referer", "https://r"), resolved.httpHeaders());
    }

    @Test
    @DisplayName("a configured path is used when it exists and reported missing when it does not")
    void honoursTheConfiguredPath(@TempDir Path temp) throws Exception {
        Path ytDlp = Files.createFile(temp.resolve("yt-dlp"));

        assertEquals(Optional.of(ytDlp), StreamResolver.locate(Optional.of(ytDlp)));
        // A configured path that is broken must not be shadowed by a PATH
        // lookup: the user pointed somewhere, and that somewhere is wrong.
        assertTrue(StreamResolver.locate(Optional.of(temp.resolve("gone"))).isEmpty());
    }
}
