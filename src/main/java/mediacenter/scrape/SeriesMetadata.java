package mediacenter.scrape;

import java.time.Instant;
import java.util.Optional;

/**
 * What the scraper found out about a series — the part worth keeping.
 *
 * <p>Lives as {@code aurora-series.json} in the series' own folder, next to
 * the episodes it describes. That is the whole persistence story: no database,
 * nothing to migrate, and the metadata travels with the folder wherever the
 * NAS moves it. Any machine that can see the share sees the answer.
 */
public record SeriesMetadata(
        long tvdbId,
        String title,
        Optional<Integer> year,
        Optional<String> overview,
        Optional<String> status,
        String scrapedFromFolderName,
        Instant scrapedAt) {

    public SeriesMetadata {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Series metadata must carry a title");
        }
        if (scrapedFromFolderName == null || scrapedFromFolderName.isBlank()) {
            throw new IllegalArgumentException("Series metadata must name the folder it was scraped from");
        }
        if (scrapedAt == null) {
            throw new IllegalArgumentException("Series metadata must carry its scrape time");
        }
        year = year == null ? Optional.empty() : year;
        overview = overview == null ? Optional.empty() : overview;
        status = status == null ? Optional.empty() : status;
    }
}
