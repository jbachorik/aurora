package mediacenter.scrape;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.media.DisplayNames;

/**
 * One movie folder, start to finish — {@link SeriesScraper}'s shorter sibling.
 *
 * <p>Same pipeline, one step lighter: there are no episodes to count, so the
 * cross-checks the provider's candidates face are the year and — where libVLC
 * could read the file — the running time against the official runtime, in
 * place of the season shape. What it loses in signals it makes up in
 * caution: the matcher's bar is higher, and a much-remade title with no year
 * anywhere is left unidentified on purpose.
 *
 * <p>Usually one film shares nothing with anyone: its evidence, its search,
 * its {@code aurora-movie.json}, all keyed by the folder alone. A folder
 * holding several films — a trilogy nobody split into subfolders — runs this
 * same pipeline once per video instead, each on the strength of its own file
 * name rather than the folder's, and each keeping its own metadata and poster
 * beside it rather than sharing the folder's.
 *
 * <p>Blocking throughout, like its sibling, and run from the same queue.
 */
public final class MovieScraper {

    private static final Logger LOG = Logger.getLogger(MovieScraper.class.getName());

    /**
     * How many search results are worth a runtime lookup — the same manners
     * as the series scraper's episode lookups, for the same reason.
     */
    private static final int CANDIDATES_TO_CHECK = 3;

    private final MovieEvidenceCollector evidenceCollector;
    private final Optional<OllamaTitleService> titleService;
    private final MetadataProvider provider;
    private final ScrapedMetadataStore store;
    private final PosterDownloader posters;

    /**
     * One identified film, paired with exactly what it was identified for —
     * the folder, when it holds that one film alone, or that film's own video
     * when the folder shares itself with others.
     */
    public record Identified(Path subject, ScrapedMetadata metadata) {
    }

    public MovieScraper(
            MovieEvidenceCollector evidenceCollector,
            Optional<OllamaTitleService> titleService,
            MetadataProvider provider,
            ScrapedMetadataStore store,
            PosterDownloader posters) {
        this.evidenceCollector = evidenceCollector;
        this.titleService = titleService;
        this.provider = provider;
        this.store = store;
        this.posters = posters;
    }

    /**
     * Scrapes one folder — one film, ordinarily, several for a trilogy sharing
     * a folder — writing each film's {@code aurora-movie.json} and poster,
     * where it has none, on success.
     *
     * @return one entry per film newly identified; a film the folder already
     *         carried metadata for is left alone, and a candidate that earned
     *         no match is simply absent, ready to try again next time
     */
    public List<Identified> scrape(Path movieFolder) {
        List<MovieEvidence> found = evidenceCollector.collect(movieFolder);
        if (found.isEmpty()) {
            return List.of();
        }
        // A single film keys everything by the folder, exactly as it always
        // has; only a folder sharing itself between several films asks each
        // one to carry its own metadata and poster instead.
        boolean sharedFolder = found.size() > 1;

        List<Identified> identified = new ArrayList<>();
        for (MovieEvidence evidence : found) {
            Path videoFile = movieFolder.resolve(evidence.videoFileName());
            boolean already = sharedFolder ? store.existsForVideo(videoFile) : store.exists(movieFolder);
            if (already) {
                continue;
            }
            identifyOne(evidence, movieFolder, videoFile, sharedFolder).ifPresent(identified::add);
        }
        return List.copyOf(identified);
    }

    private Optional<Identified> identifyOne(
            MovieEvidence evidence, Path movieFolder, Path videoFile, boolean sharedFolder) {
        Optional<TitleGuess> guess = titleService.flatMap(service -> service.guessMovieTitle(evidence));
        guess.ifPresent(titleGuess -> LOG.log(Level.FINE, () -> "Ollama reads \"" + evidence.videoFileName()
                + "\" as \"" + titleGuess.title() + "\""));

        // A shared folder's own name is the trilogy's box title, not any one
        // film's — that film's own video name is the query that finds it.
        String fallbackQuery = sharedFolder
                ? DisplayNames.forFileName(evidence.videoFileName())
                : DisplayNames.forDirectory(movieFolder);
        List<TitleCandidate> found = guess
                .map(titleGuess -> provider.searchMovies(titleGuess.title()))
                .filter(results -> !results.isEmpty())
                .orElseGet(() -> provider.searchMovies(fallbackQuery));
        if (found.isEmpty()) {
            LOG.log(Level.INFO, () -> provider.name() + " has nothing for \"" + evidence.videoFileName() + "\"");
            return Optional.empty();
        }

        // The runtime cross-check, where there is a file duration to check it
        // against; without one the per-film records would answer a question
        // nobody is asking, so they are not fetched at all.
        List<MovieMatcher.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            TitleCandidate candidate = found.get(i);
            candidates.add(new MovieMatcher.Candidate(
                    candidate,
                    evidence.duration().isPresent() && i < CANDIDATES_TO_CHECK
                            ? provider.movieRuntimeMinutes(candidate.id())
                            : Optional.empty()));
        }

        Optional<TitleCandidate> match = MovieMatcher.pick(evidence, guess, candidates);
        if (match.isEmpty()) {
            LOG.log(Level.INFO, () ->
                    "No " + provider.name() + " candidate earned \"" + evidence.videoFileName() + "\"");
            return Optional.empty();
        }

        TitleCandidate movie = match.get();
        ScrapedMetadata metadata = new ScrapedMetadata(
                provider.name(),
                movie.id(),
                movie.name(),
                movie.year(),
                movie.overview(),
                movie.status(),
                evidence.folderName(),
                Instant.now());
        Path subject = sharedFolder ? videoFile : movieFolder;
        boolean saved = sharedFolder ? store.saveForVideo(videoFile, metadata) : store.save(movieFolder, metadata);
        if (!saved) {
            return Optional.empty();
        }
        movie.posterUrl().ifPresent(url -> {
            if (sharedFolder) {
                posters.downloadIfMissingForVideo(videoFile, url);
            } else {
                posters.downloadIfMissing(movieFolder, url);
            }
        });
        LOG.log(Level.INFO, () -> "Identified \"" + evidence.videoFileName() + "\" as \""
                + movie.name() + "\" (" + provider.name() + " movie " + movie.id() + ")");
        return Optional.of(new Identified(subject, metadata));
    }
}
