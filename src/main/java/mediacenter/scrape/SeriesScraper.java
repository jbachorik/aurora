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
 * One series folder, start to finish: read the evidence, guess the title, ask
 * the metadata provider, cross-check, and write what was learned into the
 * folder itself.
 *
 * <p>The pipeline degrades a step at a time rather than failing whole. No
 * Ollama? The cleaned folder name is the search term. No episode data for a
 * candidate? The title carries the match alone. No confident match at all?
 * Nothing is written, and the next run gets to try again with nothing lost.
 *
 * <p>What is written keeps the total episode count on disk at scrape time
 * alongside the identification — {@link ScrapeService}'s one way of telling,
 * without asking anybody, that a season has arrived since and this folder is
 * worth a second conversation.
 *
 * <p>Blocking throughout — network twice over and a share in between — so it
 * runs where {@link ScrapeService} puts it, never on the JavaFX thread.
 */
public final class SeriesScraper {

    private static final Logger LOG = Logger.getLogger(SeriesScraper.class.getName());

    /**
     * How many search results are worth the episode lookups the cross-check
     * costs. The provider ranks its results; past the first few, a candidate
     * that still wins on title similarity has not appeared yet.
     */
    private static final int CANDIDATES_TO_CHECK = 3;

    private final SeriesEvidenceCollector evidenceCollector;
    private final Optional<OllamaTitleService> titleService;
    private final MetadataProvider provider;
    private final ScrapedMetadataStore store;
    private final PosterDownloader posters;

    public SeriesScraper(
            SeriesEvidenceCollector evidenceCollector,
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
     * Scrapes one folder, writing {@code aurora-series.json} — and a poster,
     * where the folder has none — on success.
     *
     * @return the stored metadata, or empty when the folder is not a series or
     *         no candidate earned the match
     */
    public Optional<ScrapedMetadata> scrape(Path seriesFolder) {
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
        List<TitleCandidate> found = guess
                .map(titleGuess -> provider.searchSeries(titleGuess.title()))
                .filter(results -> !results.isEmpty())
                .orElseGet(() -> provider.searchSeries(fallbackQuery));
        if (found.isEmpty()) {
            LOG.log(Level.INFO, () -> provider.name() + " has nothing for \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        // The cross-check the whole feature is named for: each leading candidate's
        // aired episodes per season, so a same-named series of the wrong shape loses.
        List<SeriesMatcher.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            TitleCandidate candidate = found.get(i);
            candidates.add(new SeriesMatcher.Candidate(
                    candidate,
                    i < CANDIDATES_TO_CHECK
                            ? provider.episodesPerSeason(candidate.id())
                            : Optional.empty()));
        }

        Optional<TitleCandidate> match = SeriesMatcher.pick(evidence, guess, candidates);
        if (match.isEmpty()) {
            LOG.log(Level.INFO, () -> "No " + provider.name() + " candidate earned \"" + evidence.folderName() + "\"");
            return Optional.empty();
        }

        TitleCandidate series = match.get();
        ScrapedMetadata metadata = new ScrapedMetadata(
                provider.name(),
                series.id(),
                series.name(),
                series.year(),
                series.overview(),
                series.status(),
                evidence.folderName(),
                Instant.now(),
                Optional.of(evidence.totalEpisodes()));
        if (!store.save(seriesFolder, metadata)) {
            return Optional.empty();
        }
        series.posterUrl().ifPresent(url -> posters.downloadIfMissing(seriesFolder, url));
        LOG.log(Level.INFO, () -> "Identified \"" + evidence.folderName() + "\" as \""
                + series.name() + "\" (" + provider.name() + " " + series.id() + ")");
        return Optional.of(metadata);
    }
}
