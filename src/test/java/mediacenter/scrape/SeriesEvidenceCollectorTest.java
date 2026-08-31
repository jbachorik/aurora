package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeriesEvidenceCollectorTest {

    private final SeriesEvidenceCollector collector = new SeriesEvidenceCollector();

    @Test
    @DisplayName("season folders are counted season by season")
    void readsSeasonFolders(@TempDir Path temp) throws IOException {
        Path series = Files.createDirectory(temp.resolve("Breaking Bad"));
        Path seasonOne = Files.createDirectory(series.resolve("Season 1"));
        Path seasonTwo = Files.createDirectory(series.resolve("S02"));
        Files.createFile(seasonOne.resolve("Breaking.Bad.S01E01.mkv"));
        Files.createFile(seasonOne.resolve("Breaking.Bad.S01E02.mkv"));
        Files.createFile(seasonTwo.resolve("Breaking.Bad.S02E01.mkv"));
        // Neither a video nor a folder; must not count as an episode.
        Files.createFile(seasonOne.resolve("poster.jpg"));

        SeriesEvidence evidence = collector.collect(series).orElseThrow();

        assertEquals("Breaking Bad", evidence.folderName());
        assertEquals(Map.of(1, 2, 2, 1), evidence.episodesPerSeason());
        assertEquals(3, evidence.totalEpisodes());
        assertTrue(evidence.sampleEpisodeNames().contains("Breaking.Bad.S01E01.mkv"));
    }

    @Test
    @DisplayName("a flat folder's seasons come from the episode tags themselves")
    void readsFlatTaggedEpisodes(@TempDir Path temp) throws IOException {
        Path series = Files.createDirectory(temp.resolve("Chernobyl.2019.1080p"));
        Files.createFile(series.resolve("Chernobyl.S01E01.mkv"));
        Files.createFile(series.resolve("Chernobyl.S01E02.mkv"));
        Files.createFile(series.resolve("Chernobyl.S02E01.mkv"));

        SeriesEvidence evidence = collector.collect(series).orElseThrow();

        assertEquals(Map.of(1, 2, 2, 1), evidence.episodesPerSeason());
    }

    @Test
    @DisplayName("episodes whose names open with the tag itself still count")
    void readsTagFirstEpisodes(@TempDir Path temp) throws IOException {
        Path series = Files.createDirectory(temp.resolve("Game of Thrones"));
        Files.createFile(series.resolve("S01E01-Winter Is Coming.mkv"));
        Files.createFile(series.resolve("s01e02.the.kingsroad.mkv"));
        Files.createFile(series.resolve("S02E01-The North Remembers.mkv"));

        SeriesEvidence evidence = collector.collect(series).orElseThrow();

        assertEquals(Map.of(1, 2, 2, 1), evidence.episodesPerSeason());
    }

    @Test
    @DisplayName("bare ordering prefixes read as season one")
    void readsOrderingPrefixesAsSeasonOne(@TempDir Path temp) throws IOException {
        Path series = Files.createDirectory(temp.resolve("Planet Earth"));
        Files.createFile(series.resolve("01 - From Pole to Pole.mkv"));
        Files.createFile(series.resolve("02 - Mountains.mkv"));

        SeriesEvidence evidence = collector.collect(series).orElseThrow();

        assertEquals(Map.of(1, 2), evidence.episodesPerSeason());
    }

    @Test
    @DisplayName("a folder with no episode structure is not a series")
    void refusesFoldersWithoutEpisodes(@TempDir Path temp) throws IOException {
        Path films = Files.createDirectory(temp.resolve("Films"));
        Files.createFile(films.resolve("Blade.Runner.2049.mkv"));
        Files.createDirectory(films.resolve("Documentaries"));

        assertEquals(Optional.empty(), collector.collect(films));
    }

    @Test
    @DisplayName("an unreadable folder yields nothing rather than an error")
    void refusesTheUnreadable(@TempDir Path temp) {
        assertEquals(Optional.empty(), collector.collect(temp.resolve("does-not-exist")));
    }

    @Test
    @DisplayName("a folder of extras named after a season is not that season")
    void seasonFolderNamesAreAnchored() {
        assertEquals(Optional.of(1), SeriesEvidenceCollector.seasonNumberOf("Season 1"));
        assertEquals(Optional.of(2), SeriesEvidenceCollector.seasonNumberOf("S02"));
        assertEquals(Optional.of(3), SeriesEvidenceCollector.seasonNumberOf("Series 3"));
        assertEquals(Optional.of(4), SeriesEvidenceCollector.seasonNumberOf("4"));
        assertEquals(Optional.empty(), SeriesEvidenceCollector.seasonNumberOf("Season 1 Extras"));
        assertEquals(Optional.empty(), SeriesEvidenceCollector.seasonNumberOf("Breaking Bad"));
    }
}
