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
 * cross-checks TheTVDB's candidates face are the year and — where libVLC
 * could read the file — the running time against the official runtime, in
 * place of the season shape. What it loses in signals it makes up in
 * caution: the matcher's bar is higher, and a much-remade title with no year
 * anywhere is left unidentified on purpose.
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
    private final TvdbClient tvdb;
    private final ScrapedMetadataStore store;
    private final PosterDownloader posters;

    public MovieScraper(
            MovieEvidenceCollector evidenceCollector,
            Optional<OllamaTitleService> titleService,
            TvdbClient tvdb,
            ScrapedMetadataStore store,
            PosterDownloader posters) {
        this.evidenceCollector = evidenceCollector;
        this.titleService = titleService;
        this.tvdb = tvdb;
        this.store = store;
        this.posters = posters;
    }

    /**
     * Scrapes one folder, writing {@code aurora-movie.json} — and a poster,
     * where the folder has none — on success.
     *
     * @return the stored metadata, or empty when the folder is not one film or
     *         no candidate earned the match
     */
    public Optional<ScrapedMetadata> scrape(Path movieFolder) {
        Optional<MovieEvidence> collected = evidenceCollector.collect(movieFolder);
        if (collected.isEmpty()) {
            return Optional.empty();
        }
        MovieEvidence evidence = collected.get();

        Optional<TitleGuess> guess = titleService.flatMap(service -> service.guessMovieTitle(evidence));
        guess.ifPresent(titleGuess -> LOG.log(Level.FINE, () -> "Ollama reads \"" + evidence.folderName()
                + "\" as \"" + titleGuess.title() + "\""));

        String fallbackQuery = DisplayNames.forDirectory(movieFolder);
        List<TvdbCandidate> found = guess
                .map(titleGuess -> tvdb.searchMovies(titleGuess.title()))
                .filter(results -> !results.isEmpty())
                .orElseGet(() -> tvdb.searchMovies(fallbackQuery));
        if (found.isEmpty()) {
            LOG.log(Level.INFO, () -> "TheTVDB has nothing for \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        // The runtime cross-check, where there is a file duration to check it
        // against; without one the extended records would answer a question
        // nobody is asking, so they are not fetched at all.
        List<MovieMatcher.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            TvdbCandidate candidate = found.get(i);
            candidates.add(new MovieMatcher.Candidate(
                    candidate,
                    evidence.duration().isPresent() && i < CANDIDATES_TO_CHECK
                            ? tvdb.movieRuntimeMinutes(candidate.tvdbId())
                            : Optional.empty()));
        }

        Optional<TvdbCandidate> match = MovieMatcher.pick(evidence, guess, candidates);
        if (match.isEmpty()) {
            LOG.log(Level.INFO, () -> "No TheTVDB candidate earned \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        TvdbCandidate movie = match.get();
        ScrapedMetadata metadata = new ScrapedMetadata(
                movie.tvdbId(),
                movie.name(),
                movie.year(),
                movie.overview(),
                movie.status(),
                evidence.folderName(),
                Instant.now());
        if (!store.save(movieFolder, metadata)) {
            return Optional.empty();
        }
        movie.posterUrl().ifPresent(url -> posters.downloadIfMissing(movieFolder, url));
        LOG.log(Level.INFO, () -> "Identified \"" + evidence.folderName() + "\" as \""
                + movie.name() + "\" (TheTVDB movie " + movie.tvdbId() + ")");
        return Optional.of(metadata);
    }
}
