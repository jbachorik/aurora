package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.scrape.EpisodeTags.SeasonEpisode;

class EpisodeTagsTest {

    @Test
    @DisplayName("every tag shape SeriesFolders recognises yields its numbers here")
    void parsesTheRecognisedShapes() {
        assertEquals(Optional.of(new SeasonEpisode(1, 1)),
                EpisodeTags.parse("Breaking.Bad.S01E01.Pilot.mkv"));
        assertEquals(Optional.of(new SeasonEpisode(1, 2)),
                EpisodeTags.parse("My Name Is Earl - S01.E02 - Quitter [DVDRip-XviD].avi"));
        assertEquals(Optional.of(new SeasonEpisode(1, 1)),
                EpisodeTags.parse("Show.S01EP01.mkv"));
        assertEquals(Optional.of(new SeasonEpisode(3, 12)),
                EpisodeTags.parse("show 3x12.mkv"));
        assertEquals(Optional.of(new SeasonEpisode(1, 1)),
                EpisodeTags.parse("Show - S01 - E01 - Pilot.mkv"));
    }

    @Test
    @DisplayName("a name that opens with its tag — no series name in front — still parses")
    void parsesTagFirstNames() {
        // The rest of the title follows a dash…
        assertEquals(Optional.of(new SeasonEpisode(1, 5)),
                EpisodeTags.parse("S01E05-The Wolf and the Lion.mkv"));
        // …or dots, the other shape rippers write.
        assertEquals(Optional.of(new SeasonEpisode(2, 3)),
                EpisodeTags.parse("s02e03.The.North.Remembers.mkv"));
        assertEquals(Optional.of(new SeasonEpisode(1, 1)),
                EpisodeTags.parse("1x01 Pilot.mkv"));
    }

    @Test
    @DisplayName("what is not a tag yields nothing, exactly as it does not chain")
    void refusesWhatIsNotATag() {
        assertTrue(EpisodeTags.parse("movie.1280x720.mkv").isEmpty());
        assertTrue(EpisodeTags.parse("Series 1 - Episode 1.mkv").isEmpty());
        assertTrue(EpisodeTags.parse("Blade.Runner.2049.mkv").isEmpty());
        assertTrue(EpisodeTags.parse(null).isEmpty());
    }

    @Test
    @DisplayName("an ordering prefix is an episode number with no season of its own")
    void parsesOrderingPrefixes() {
        assertEquals(Optional.of(2), EpisodeTags.parseOrderingPrefix("02 - Dead Mans Chest.mkv"));
        assertEquals(Optional.of(1), EpisodeTags.parseOrderingPrefix("01. Pilot.mkv"));
        // A leading year is a title, not ordering — the same call DisplayNames makes.
        assertTrue(EpisodeTags.parseOrderingPrefix("2001 - A Space Odyssey.mkv").isEmpty());
        assertTrue(EpisodeTags.parseOrderingPrefix("Pilot.mkv").isEmpty());
    }
}
