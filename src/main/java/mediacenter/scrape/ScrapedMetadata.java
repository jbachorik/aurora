package mediacenter.scrape;

import java.time.Instant;
import java.util.Optional;

/**
 * What the scraper found out about a series or a film — the part worth keeping.
 * The same facts serve both; which kind of thing they describe is said by the
 * file the {@link ScrapedMetadataStore} keeps them in.
 *
 * <p>Lives in the title's own folder, next to what it describes. That is the
 * whole persistence story: no database, nothing to migrate, and the metadata
 * travels with the folder wherever the NAS moves it. Any machine that can see
 * the share sees the answer.
 */
public record ScrapedMetadata(
        long tvdbId,
        String title,
        Optional<Integer> year,
        Optional<String> overview,
        Optional<String> status,
        String scrapedFromFolderName,
        Instant scrapedAt) {

    public ScrapedMetadata {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Scraped metadata must carry a title");
        }
        if (scrapedFromFolderName == null || scrapedFromFolderName.isBlank()) {
            throw new IllegalArgumentException("Scraped metadata must name the folder it was scraped from");
        }
        if (scrapedAt == null) {
            throw new IllegalArgumentException("Scraped metadata must carry its scrape time");
        }
        year = year == null ? Optional.empty() : year;
        overview = overview == null ? Optional.empty() : overview;
        status = status == null ? Optional.empty() : status;
    }
}
