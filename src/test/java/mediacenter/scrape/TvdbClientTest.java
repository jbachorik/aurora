package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.json.Json;
import mediacenter.json.JsonException;

class TvdbClientTest {

    @Test
    @DisplayName("a search response is read into candidates, string-typed ids and all")
    void parsesSearchResults() throws JsonException {
        List<SeriesCandidate> candidates = TvdbClient.parseSearch(Json.parseObject("""
                {"status": "success", "data": [
                  {"tvdb_id": "81189", "name": "Breaking Bad", "year": "2008",
                   "overview": "A chemistry teacher.", "status": "Ended",
                   "image_url": "https://artworks.thetvdb.com/banners/posters/81189-10.jpg",
                   "aliases": ["Metastasis", ""]},
                  {"id": "series-999", "name": "No id, skipped"},
                  {"tvdb_id": "273181", "name": "Better Call Saul"}
                ]}
                """));

        assertEquals(2, candidates.size());
        SeriesCandidate first = candidates.getFirst();
        assertEquals(81189, first.tvdbId());
        assertEquals("Breaking Bad", first.name());
        assertEquals(Optional.of(2008), first.year());
        assertEquals(Optional.of("A chemistry teacher."), first.overview());
        assertEquals(Optional.of("Ended"), first.status());
        assertEquals(Optional.of("https://artworks.thetvdb.com/banners/posters/81189-10.jpg"),
                first.posterUrl());
        // The blank alias is dropped; the real one survives.
        assertEquals(List.of("Metastasis"), first.aliases());
        assertEquals("Better Call Saul", candidates.get(1).name());
        assertEquals(Optional.empty(), candidates.get(1).year());
    }

    @Test
    @DisplayName("episodes are counted into their seasons, specials included as season zero")
    void countsEpisodesPerSeason() throws JsonException {
        Map<Integer, Integer> counts = new HashMap<>();
        TvdbClient.countEpisodes(Json.parseObject("""
                {"data": {"episodes": [
                  {"id": 1, "seasonNumber": 0},
                  {"id": 2, "seasonNumber": 1},
                  {"id": 3, "seasonNumber": 1},
                  {"id": 4, "seasonNumber": 2},
                  {"id": 5}
                ]}}
                """), counts);

        assertEquals(Map.of(0, 1, 1, 2, 2, 1), counts);
    }

    @Test
    @DisplayName("a second page is announced by the links, and only by the links")
    void readsThePagePointer() throws JsonException {
        assertTrue(TvdbClient.hasNextPage(Json.parseObject(
                "{\"links\": {\"next\": \"https://api4.thetvdb.com/v4/series/1/episodes/default?page=1\"}}")));
        assertFalse(TvdbClient.hasNextPage(Json.parseObject("{\"links\": {\"next\": null}}")));
        assertFalse(TvdbClient.hasNextPage(Json.parseObject("{\"links\": {}}")));
        assertFalse(TvdbClient.hasNextPage(Json.parseObject("{}")));
    }
}
