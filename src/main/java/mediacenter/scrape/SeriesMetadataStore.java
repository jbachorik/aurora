package mediacenter.scrape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.JsonException;
import mediacenter.json.JsonFiles;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * Reads and writes a series folder's {@code aurora-series.json}.
 *
 * <p>The file is deliberately plain: two-space JSON a person can open over the
 * share and correct by hand, which — with no database anywhere — is also the
 * entire administration story. A file that cannot be parsed reads as "not
 * scraped yet", so a corrupted one costs a re-scrape and nothing else.
 */
public final class SeriesMetadataStore {

    private static final Logger LOG = Logger.getLogger(SeriesMetadataStore.class.getName());

    /**
     * Prefixed with the application's name so a folder full of other tools'
     * files — Kodi's {@code tvshow.nfo}, Plex's leavings — never collides.
     */
    public static final String FILE_NAME = "aurora-series.json";

    /** Whether a folder already carries scraped metadata, however old. */
    public boolean exists(Path seriesFolder) {
        return Files.isRegularFile(seriesFolder.resolve(FILE_NAME));
    }

    /** The folder's stored metadata, or empty when there is none worth reading. */
    public Optional<SeriesMetadata> load(Path seriesFolder) {
        Path file = seriesFolder.resolve(FILE_NAME);
        try {
            return JsonFiles.readObject(file).flatMap(SeriesMetadataStore::fromJson);
        } catch (IOException | JsonException e) {
            LOG.log(Level.FINE, e, () -> "Could not read " + file);
            return Optional.empty();
        }
    }

    /** Writes the metadata into the series folder. Returns false when the share refused. */
    public boolean save(Path seriesFolder, SeriesMetadata metadata) {
        Path file = seriesFolder.resolve(FILE_NAME);
        try {
            JsonFiles.write(file, toJson(metadata));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not write " + file);
            return false;
        }
    }

    // -- mapping ------------------------------------------------------------

    public static JsonObject toJson(SeriesMetadata metadata) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        members.put("tvdbId", new JsonNumber(metadata.tvdbId()));
        members.put("title", new JsonString(metadata.title()));
        metadata.year().ifPresent(year -> members.put("year", new JsonNumber(year)));
        metadata.overview().ifPresent(overview -> members.put("overview", new JsonString(overview)));
        metadata.status().ifPresent(status -> members.put("status", new JsonString(status)));
        members.put("scrapedFromFolderName", new JsonString(metadata.scrapedFromFolderName()));
        members.put("scrapedAt", new JsonString(metadata.scrapedAt().toString()));
        return new JsonObject(members);
    }

    public static Optional<SeriesMetadata> fromJson(JsonObject document) {
        Optional<Long> tvdbId = document.longValue("tvdbId");
        Optional<String> title = document.nonBlankString("title");
        if (tvdbId.isEmpty() || title.isEmpty()) {
            return Optional.empty();
        }
        Instant scrapedAt;
        try {
            scrapedAt = document.nonBlankString("scrapedAt").map(Instant::parse).orElse(Instant.EPOCH);
        } catch (DateTimeParseException e) {
            // A hand-mangled timestamp does not unscrape the series.
            scrapedAt = Instant.EPOCH;
        }
        return Optional.of(new SeriesMetadata(
                tvdbId.get(),
                title.get(),
                document.longValue("year").map(Long::intValue),
                document.nonBlankString("overview"),
                document.nonBlankString("status"),
                document.nonBlankString("scrapedFromFolderName").orElse(title.get()),
                scrapedAt));
    }
}
