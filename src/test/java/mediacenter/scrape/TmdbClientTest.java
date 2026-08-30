package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.json.Json;
import mediacenter.json.JsonException;

class TmdbClientTest {

    @Test
    @DisplayName("a TV search response is read into candidates, original names as aliases")
    void parsesSeriesSearchResults() throws JsonException {
        List<TitleCandidate> candidates = TmdbClient.parseSeriesSearch(Json.parseObject("""
                {"page": 1, "results": [
                  {"id": 1396, "name": "Breaking Bad", "original_name": "Breaking Bad",
                   "first_air_date": "2008-01-20", "overview": "A chemistry teacher.",
                   "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"},
                  {"id": 71446, "name": "Money Heist", "original_name": "La Casa de Papel",
                   "first_air_date": "2017-05-02"},
                  {"name": "No id, skipped"}
                ]}
                """));

        assertEquals(2, candidates.size());
        TitleCandidate first = candidates.getFirst();
        assertEquals(1396, first.id());
        assertEquals("Breaking Bad", first.name());
        assertEquals(Optional.of(2008), first.year());
        assertEquals(Optional.of("A chemistry teacher."), first.overview());
        assertEquals(Optional.of(TmdbClient.IMAGE_BASE_URL + "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
                first.posterUrl());
        // The original name repeats the name and is not worth carrying twice…
        assertEquals(List.of(), first.aliases());
        // …but a differing one is the alias that lets a local-language folder match.
        assertEquals(List.of("La Casa de Papel"), candidates.get(1).aliases());
        assertEquals(Optional.empty(), candidates.get(1).posterUrl());
    }

    @Test
    @DisplayName("a movie search reads the same way through its differently named fields")
    void parsesMovieSearchResults() throws JsonException {
        List<TitleCandidate> candidates = TmdbClient.parseMovieSearch(Json.parseObject("""
                {"results": [
                  {"id": 335984, "title": "Blade Runner 2049", "original_title": "Blade Runner 2049",
                   "release_date": "2017-10-04", "overview": "A new blade runner."}
                ]}
                """));

        assertEquals(335984, candidates.getFirst().id());
        assertEquals("Blade Runner 2049", candidates.getFirst().name());
        assertEquals(Optional.of(2017), candidates.getFirst().year());
    }

    @Test
    @DisplayName("a series' record carries its season sizes whole, specials included")
    void parsesEpisodesPerSeason() throws JsonException {
        Optional<Map<Integer, Integer>> counts = TmdbClient.parseEpisodesPerSeason(Json.parseObject("""
                {"id": 1396, "name": "Breaking Bad", "seasons": [
                  {"season_number": 0, "episode_count": 9},
                  {"season_number": 1, "episode_count": 7},
                  {"season_number": 2, "episode_count": 13},
                  {"season_number": 3, "episode_count": 0}
                ]}
                """));

        assertEquals(Optional.of(Map.of(0, 9, 1, 7, 2, 13)), counts);
        // A record with no seasons at all is no opinion, not an empty series.
        assertTrue(TmdbClient.parseEpisodesPerSeason(
                Json.parseObject("{\"id\": 1396, \"seasons\": []}")).isEmpty());
    }

    @Test
    @DisplayName("a film's runtime comes straight off its record")
    void parsesMovieRuntimes() throws JsonException {
        assertEquals(Optional.of(164),
                TmdbClient.parseMovieRuntime(Json.parseObject("{\"id\": 335984, \"runtime\": 164}")));
        assertEquals(Optional.empty(),
                TmdbClient.parseMovieRuntime(Json.parseObject("{\"id\": 335984, \"runtime\": 0}")));
        assertEquals(Optional.empty(),
                TmdbClient.parseMovieRuntime(Json.parseObject("{\"id\": 335984}")));
    }

    @Test
    @DisplayName("the year is the date's leading digits, or nothing at all")
    void readsYearsOutOfDates() {
        assertEquals(Optional.of(2008), TmdbClient.yearOf(Optional.of("2008-01-20")));
        assertEquals(Optional.of(1995), TmdbClient.yearOf(Optional.of("1995")));
        assertEquals(Optional.empty(), TmdbClient.yearOf(Optional.of("")));
        assertEquals(Optional.empty(), TmdbClient.yearOf(Optional.of("n/a")));
        assertEquals(Optional.empty(), TmdbClient.yearOf(Optional.empty()));
    }
}
