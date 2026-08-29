package mediacenter.scrape;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a series folder says about itself, read entirely from disk.
 *
 * <p>This is everything the scraper knows before it asks anyone: the folder's
 * own name, how many episodes each season holds, and a few episode file names
 * verbatim. The name seeds the title guess; the counts and samples are the
 * cross-check — a candidate series whose seasons cannot contain what is on
 * disk is not this series, however similar its title reads.
 */
public record SeriesEvidence(
        String folderName,
        SortedMap<Integer, Integer> episodesPerSeason,
        List<String> sampleEpisodeNames) {

    public SeriesEvidence {
        if (folderName == null || folderName.isBlank()) {
            throw new IllegalArgumentException("Series folder name must not be blank");
        }
        episodesPerSeason = Collections.unmodifiableSortedMap(
                new TreeMap<>(episodesPerSeason == null ? new TreeMap<>() : episodesPerSeason));
        sampleEpisodeNames = sampleEpisodeNames == null ? List.of() : List.copyOf(sampleEpisodeNames);
    }

    /** How many episodes the folder holds across all its seasons. */
    public int totalEpisodes() {
        return episodesPerSeason.values().stream().mapToInt(Integer::intValue).sum();
    }
}
