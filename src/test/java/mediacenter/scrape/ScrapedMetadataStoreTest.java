package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScrapedMetadataStoreTest {

    private final ScrapedMetadataStore store = ScrapedMetadataStore.series();

    private static ScrapedMetadata metadata() {
        return new ScrapedMetadata(
                81189,
                "Breaking Bad",
                Optional.of(2008),
                Optional.of("A chemistry teacher turns to crime."),
                Optional.of("Ended"),
                "Breaking.Bad.S01-S05.1080p",
                Instant.parse("2026-08-29T10:15:30Z"));
    }

    @Test
    @DisplayName("what the scraper learned survives in the series folder itself")
    void roundTripsThroughTheSeriesFolder(@TempDir Path temp) {
        assertFalse(store.exists(temp));

        assertTrue(store.save(temp, metadata()));

        assertTrue(store.exists(temp));
        assertTrue(Files.isRegularFile(temp.resolve(ScrapedMetadataStore.SERIES_FILE_NAME)));
        assertEquals(Optional.of(metadata()), store.load(temp));
    }

    @Test
    @DisplayName("the optional fields stay optional through the round trip")
    void roundTripsWithoutTheOptionalFields(@TempDir Path temp) {
        ScrapedMetadata bare = new ScrapedMetadata(
                360893, "Chernobyl", Optional.empty(), Optional.empty(), Optional.empty(),
                "Chernobyl", Instant.parse("2026-08-29T10:15:30Z"));

        assertTrue(store.save(temp, bare));

        assertEquals(Optional.of(bare), store.load(temp));
    }

    @Test
    @DisplayName("a folder without the file simply has not been scraped")
    void anAbsentFileIsNoMetadata(@TempDir Path temp) {
        assertEquals(Optional.empty(), store.load(temp));
    }

    @Test
    @DisplayName("a movie's file and a series' file live side by side without meeting")
    void theTwoKindsKeepTheirOwnFiles(@TempDir Path temp) {
        ScrapedMetadataStore movies = ScrapedMetadataStore.movies();

        assertTrue(movies.save(temp, metadata()));

        assertTrue(Files.isRegularFile(temp.resolve(ScrapedMetadataStore.MOVIE_FILE_NAME)));
        assertTrue(movies.exists(temp));
        // The series store does not see it: a folder scraped as a film has
        // not been scraped as a series.
        assertFalse(store.exists(temp));
        assertEquals(Optional.of(metadata()), movies.load(temp));
    }

    @Test
    @DisplayName("a corrupted file reads as not scraped, never as an error")
    void aCorruptFileIsNoMetadata(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve(ScrapedMetadataStore.SERIES_FILE_NAME), "{ not json");
        assertEquals(Optional.empty(), store.load(temp));

        Files.writeString(temp.resolve(ScrapedMetadataStore.SERIES_FILE_NAME), "{\"title\": \"No id\"}");
        assertEquals(Optional.empty(), store.load(temp));
    }

    @Test
    @DisplayName("a hand-mangled timestamp costs the timestamp, not the metadata")
    void aBrokenTimestampIsForgiven(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve(ScrapedMetadataStore.SERIES_FILE_NAME), """
                {"tvdbId": 81189, "title": "Breaking Bad", "scrapedAt": "yesterday-ish"}
                """);

        ScrapedMetadata loaded = store.load(temp).orElseThrow();

        assertEquals("Breaking Bad", loaded.title());
        assertEquals(Instant.EPOCH, loaded.scrapedAt());
    }
}
