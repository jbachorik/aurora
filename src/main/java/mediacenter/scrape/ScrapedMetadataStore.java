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
import mediacenter.media.VideoFiles;

/**
 * Reads and writes a scraped title's metadata file — {@code aurora-series.json}
 * for a series, {@code aurora-movie.json} for a film, one per folder. Two
 * names, one shape: the file also says which kind of thing its subject is,
 * without a field to say it in.
 *
 * <p>A folder holding more than one film — a trilogy nobody split into its own
 * subfolders — cannot share one such file between them, so each video gets its
 * own sidecar instead: {@code The Matrix.aurora-movie.json} beside
 * {@code The Matrix.mkv}. Series never need this; a season is not a separate
 * film to identify on its own.
 *
 * <p>The file is deliberately plain: two-space JSON a person can open over the
 * share and correct by hand, which — with no database anywhere — is also the
 * entire administration story. A file that cannot be parsed reads as "not
 * scraped yet", so a corrupted one costs a re-scrape and nothing else.
 */
public final class ScrapedMetadataStore {

    private static final Logger LOG = Logger.getLogger(ScrapedMetadataStore.class.getName());

    /**
     * Prefixed with the application's name so a folder full of other tools'
     * files — Kodi's {@code tvshow.nfo}, Plex's leavings — never collides.
     */
    public static final String SERIES_FILE_NAME = "aurora-series.json";
    public static final String MOVIE_FILE_NAME = "aurora-movie.json";

    private final String fileName;

    private ScrapedMetadataStore(String fileName) {
        this.fileName = fileName;
    }

    /** The store a series folder's metadata lives in. */
    public static ScrapedMetadataStore series() {
        return new ScrapedMetadataStore(SERIES_FILE_NAME);
    }

    /** The store a movie folder's metadata lives in. */
    public static ScrapedMetadataStore movies() {
        return new ScrapedMetadataStore(MOVIE_FILE_NAME);
    }

    /** Whether a folder already carries scraped metadata, however old. */
    public boolean exists(Path folder) {
        return Files.isRegularFile(metadataFile(folder));
    }

    /** The folder's stored metadata, or empty when there is none worth reading. */
    public Optional<ScrapedMetadata> load(Path folder) {
        return read(metadataFile(folder));
    }

    /** Writes the metadata into the folder. Returns false when the share refused. */
    public boolean save(Path folder, ScrapedMetadata metadata) {
        return write(metadataFile(folder), metadata);
    }

    /**
     * Whether one film sharing its folder with others already carries its own
     * metadata. A folder holding a trilogy scrapes each video on its own, so
     * "already scraped" is a question about the video, never the folder.
     */
    public boolean existsForVideo(Path videoFile) {
        return Files.isRegularFile(sidecarFile(videoFile));
    }

    /** One video's own stored metadata, or empty when there is none worth reading. */
    public Optional<ScrapedMetadata> loadForVideo(Path videoFile) {
        return read(sidecarFile(videoFile));
    }

    /** Writes one video's own metadata beside it. Returns false when the share refused. */
    public boolean saveForVideo(Path videoFile, ScrapedMetadata metadata) {
        return write(sidecarFile(videoFile), metadata);
    }

    /**
     * Whether the folder's metadata file was touched by something other than
     * this class's own {@link #save} after it wrote {@code onRecord} — a hand
     * correction over the share, most likely — which an incremental re-scrape
     * must never overwrite.
     */
    public boolean handEditedSince(Path folder, ScrapedMetadata onRecord) {
        try {
            Instant touchedAt = Files.getLastModifiedTime(metadataFile(folder)).toInstant();
            // A couple of seconds' slack: some shares keep only whole-second —
            // or coarser — timestamps, and this class's own write must never
            // read back as somebody else's edit.
            return touchedAt.isAfter(onRecord.scrapedAt().plusSeconds(2));
        } catch (IOException e) {
            return false;
        }
    }

    private Path metadataFile(Path folder) {
        return folder.resolve(fileName);
    }

    private Path sidecarFile(Path videoFile) {
        String baseName = VideoFiles.withoutExtension(videoFile.getFileName().toString());
        return videoFile.resolveSibling(baseName + "." + fileName);
    }

    private Optional<ScrapedMetadata> read(Path file) {
        try {
            return JsonFiles.readObject(file).flatMap(ScrapedMetadataStore::fromJson);
        } catch (IOException | JsonException e) {
            LOG.log(Level.FINE, e, () -> "Could not read " + file);
            return Optional.empty();
        }
    }

    private boolean write(Path file, ScrapedMetadata metadata) {
        try {
            JsonFiles.write(file, toJson(metadata));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not write " + file);
            return false;
        }
    }

    // -- mapping ------------------------------------------------------------

    public static JsonObject toJson(ScrapedMetadata metadata) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        members.put("provider", new JsonString(metadata.provider()));
        members.put("providerId", new JsonNumber(metadata.providerId()));
        members.put("title", new JsonString(metadata.title()));
        metadata.year().ifPresent(year -> members.put("year", new JsonNumber(year)));
        metadata.overview().ifPresent(overview -> members.put("overview", new JsonString(overview)));
        metadata.status().ifPresent(status -> members.put("status", new JsonString(status)));
        members.put("scrapedFromFolderName", new JsonString(metadata.scrapedFromFolderName()));
        members.put("scrapedAt", new JsonString(metadata.scrapedAt().toString()));
        metadata.diskEpisodeCount().ifPresent(count -> members.put("diskEpisodeCount", new JsonNumber(count)));
        return new JsonObject(members);
    }

    public static Optional<ScrapedMetadata> fromJson(JsonObject document) {
        // Files written before TMDB existed carry only "tvdbId"; the name of
        // the field is the name of the database.
        Optional<Long> providerId = document.longValue("providerId")
                .or(() -> document.longValue("tvdbId"));
        Optional<String> title = document.nonBlankString("title");
        if (providerId.isEmpty() || title.isEmpty()) {
            return Optional.empty();
        }
        String provider = document.nonBlankString("provider").orElse("TheTVDB");
        Instant scrapedAt;
        try {
            scrapedAt = document.nonBlankString("scrapedAt").map(Instant::parse).orElse(Instant.EPOCH);
        } catch (DateTimeParseException e) {
            // A hand-mangled timestamp does not unscrape the folder.
            scrapedAt = Instant.EPOCH;
        }
        return Optional.of(new ScrapedMetadata(
                provider,
                providerId.get(),
                title.get(),
                document.longValue("year").map(Long::intValue),
                document.nonBlankString("overview"),
                document.nonBlankString("status"),
                document.nonBlankString("scrapedFromFolderName").orElse(title.get()),
                scrapedAt,
                document.longValue("diskEpisodeCount").map(Long::intValue)));
    }
}
