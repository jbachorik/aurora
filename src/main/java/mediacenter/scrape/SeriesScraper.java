package mediacenter.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.media.ArtworkResolver;
import mediacenter.media.DisplayNames;

/**
 * One folder, start to finish: read the evidence, guess the title, ask
 * TheTVDB, cross-check, and write what was learned into the folder itself.
 *
 * <p>The pipeline degrades a step at a time rather than failing whole. No
 * Ollama? The cleaned folder name is the search term. No episode data for a
 * candidate? The title carries the match alone. No confident match at all?
 * Nothing is written, and the next run gets to try again with nothing lost.
 *
 * <p>Blocking throughout — network twice over and a share in between — so it
 * runs where {@link SeriesScrapeService} puts it, never on the JavaFX thread.
 */
public final class SeriesScraper {

    private static final Logger LOG = Logger.getLogger(SeriesScraper.class.getName());

    /**
     * How many search results are worth the episode lookups the cross-check
     * costs. TheTVDB ranks its results; past the first few, a candidate that
     * still wins on title similarity has not appeared yet.
     */
    private static final int CANDIDATES_TO_CHECK = 3;

    private final SeriesEvidenceCollector evidenceCollector;
    private final Optional<OllamaTitleService> titleService;
    private final TvdbClient tvdb;
    private final SeriesMetadataStore store;
    private final HttpClient posterClient;

    public SeriesScraper(
            SeriesEvidenceCollector evidenceCollector,
            Optional<OllamaTitleService> titleService,
            TvdbClient tvdb,
            SeriesMetadataStore store) {
        this.evidenceCollector = evidenceCollector;
        this.titleService = titleService;
        this.tvdb = tvdb;
        this.store = store;
        this.posterClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Scrapes one folder, writing {@code aurora-series.json} — and a poster,
     * where the folder has none — on success.
     *
     * @return the stored metadata, or empty when the folder is not a series or
     *         no candidate earned the match
     */
    public Optional<SeriesMetadata> scrape(Path seriesFolder) {
        Optional<SeriesEvidence> collected = evidenceCollector.collect(seriesFolder);
        if (collected.isEmpty()) {
            return Optional.empty();
        }
        SeriesEvidence evidence = collected.get();

        Optional<TitleGuess> guess = titleService.flatMap(service -> service.guessTitle(evidence));
        guess.ifPresent(titleGuess -> LOG.log(Level.FINE, () -> "Ollama reads \"" + evidence.folderName()
                + "\" as \"" + titleGuess.title() + "\""));

        // The model's title is the better search term when it exists; the folder
        // name — cleaned the way the UI cleans it — is the term that always exists.
        String fallbackQuery = DisplayNames.forDirectory(seriesFolder);
        List<SeriesCandidate> found = guess
                .map(titleGuess -> tvdb.searchSeries(titleGuess.title()))
                .filter(results -> !results.isEmpty())
                .orElseGet(() -> tvdb.searchSeries(fallbackQuery));
        if (found.isEmpty()) {
            LOG.log(Level.INFO, () -> "TheTVDB has nothing for \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        // The cross-check the whole feature is named for: each leading candidate's
        // aired episodes per season, so a same-named series of the wrong shape loses.
        List<SeriesMatcher.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            SeriesCandidate candidate = found.get(i);
            candidates.add(new SeriesMatcher.Candidate(
                    candidate,
                    i < CANDIDATES_TO_CHECK
                            ? tvdb.episodesPerSeason(candidate.tvdbId())
                            : Optional.empty()));
        }

        Optional<SeriesCandidate> match = SeriesMatcher.pick(evidence, guess, candidates);
        if (match.isEmpty()) {
            LOG.log(Level.INFO, () -> "No TheTVDB candidate earned \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        SeriesCandidate series = match.get();
        SeriesMetadata metadata = new SeriesMetadata(
                series.tvdbId(),
                series.name(),
                series.year(),
                series.overview(),
                series.status(),
                evidence.folderName(),
                Instant.now());
        if (!store.save(seriesFolder, metadata)) {
            return Optional.empty();
        }
        series.posterUrl().ifPresent(url -> downloadPosterIfMissing(seriesFolder, url));
        LOG.log(Level.INFO, () -> "Identified \"" + evidence.folderName() + "\" as \""
                + series.name() + "\" (TheTVDB " + series.tvdbId() + ")");
        return Optional.of(metadata);
    }

    /**
     * Fetches the poster into the folder — but never over artwork already
     * there, whoever put it there: a cover the user chose beats a scraped one.
     */
    private void downloadPosterIfMissing(Path seriesFolder, String posterUrl) {
        if (ArtworkResolver.selectCover(listFileNames(seriesFolder)).isPresent()) {
            return;
        }
        String extension = posterUrl.toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpg";
        Path target = seriesFolder.resolve("poster." + extension);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(posterUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = posterClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length == 0) {
                LOG.log(Level.INFO, () -> "Poster download answered HTTP " + response.statusCode()
                        + " for " + posterUrl);
                return;
            }
            Files.write(target, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // The metadata is already saved; a poster that would not download
            // is retried the day the user deletes the metadata file, and the
            // tile falls back to its generated placeholder meanwhile.
            LOG.log(Level.INFO, e, () -> "Could not download the poster for " + seriesFolder);
        }
    }

    private static List<String> listFileNames(Path directory) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null) {
                    names.add(name.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return names;
    }
}
