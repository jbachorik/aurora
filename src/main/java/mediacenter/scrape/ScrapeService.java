package mediacenter.scrape;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.config.ScraperSettings;

/**
 * Queues folder scrapes behind browsing, one at a time.
 *
 * <p>Browsing a TV or Movies shelf offers every folder on it to this service
 * — as a series or as a film, by the root's declaration — and the service
 * decides which are worth a scrape. Cheap disqualifications first — scraping
 * switched off, folder already carrying its metadata file, folder already
 * tried since startup — and only then the real work, serialised so twenty new
 * titles never open twenty conversations with TheTVDB at once.
 *
 * <p>"Already tried" is remembered per run and not on disk: a folder that
 * found no confident match is left alone until the next start rather than
 * being retried on every visit, but is never marked failed forever — the
 * missing season, or the year a rename adds, may arrive next week.
 */
public final class ScrapeService {

    private static final Logger LOG = Logger.getLogger(ScrapeService.class.getName());

    /** Which pipeline a folder goes down; the root it sits under decides. */
    private enum Kind { SERIES, MOVIE }

    private final Supplier<ScraperSettings> settings;
    private final Executor backgroundExecutor;
    private final Executor notificationExecutor;
    private final MediaDurationProbe durationProbe;
    private final ScrapedMetadataStore seriesStore = ScrapedMetadataStore.series();
    private final ScrapedMetadataStore movieStore = ScrapedMetadataStore.movies();
    private final PosterDownloader posters = new PosterDownloader();

    /** One scrape at a time; the queue is the executor's. */
    private final Semaphore oneAtATime = new Semaphore(1);

    private final Set<Path> attempted = ConcurrentHashMap.newKeySet();

    /** Who to tell when a folder gains metadata; the browse page showing it, mostly. */
    private volatile Consumer<Path> onScraped = folder -> { };

    /**
     * @param settings             read per scrape, so Settings changes apply to
     *                             the very next folder without a restart
     * @param backgroundExecutor   where the blocking work runs
     * @param notificationExecutor where {@link #setOnScraped} callbacks run —
     *                             the JavaFX thread, in the application
     * @param durationProbe        how a film's running time is read, when it
     *                             can be — libVLC in the application,
     *                             {@link MediaDurationProbe#none()} anywhere
     *                             a duration has no way of being known
     */
    public ScrapeService(
            Supplier<ScraperSettings> settings,
            Executor backgroundExecutor,
            Executor notificationExecutor,
            MediaDurationProbe durationProbe) {
        this.settings = settings;
        this.backgroundExecutor = backgroundExecutor;
        this.notificationExecutor = notificationExecutor;
        this.durationProbe = durationProbe;
    }

    /** Registers the one interested party, replacing the previous one. */
    public void setOnScraped(Consumer<Path> listener) {
        this.onScraped = listener == null ? folder -> { } : listener;
    }

    /** Offers a folder from a TV shelf. Returns immediately. */
    public void scrapeSeriesIfNeeded(Path seriesFolder) {
        offer(seriesFolder, Kind.SERIES);
    }

    /** Offers a folder from a Movies shelf. Returns immediately. */
    public void scrapeMovieIfNeeded(Path movieFolder) {
        offer(movieFolder, Kind.MOVIE);
    }

    private void offer(Path folder, Kind kind) {
        Path folderName = folder.getFileName();
        if (folderName == null
                || SeriesEvidenceCollector.seasonNumberOf(folderName.toString()).isPresent()) {
            // A "Season 2" folder is part of a series, not a title of its own;
            // its parent is the folder whose name means something. The same
            // guard on a Movies shelf keeps a misfiled season from being
            // identified as a film called "Season 2".
            return;
        }
        if (!settings.get().readyToScrape() || !attempted.add(folder)) {
            return;
        }
        try {
            backgroundExecutor.execute(() -> scrapeQuietly(folder, kind));
        } catch (RejectedExecutionException e) {
            // Shutdown; the folder will be offered again next run.
        }
    }

    private void scrapeQuietly(Path folder, Kind kind) {
        try {
            oneAtATime.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            ScraperSettings current = settings.get();
            // Both looked at again behind the semaphore: the user may have
            // switched scraping off — or the previous scrape may have been
            // this very folder, queued twice from two visits — while this
            // one waited its turn.
            if (!current.readyToScrape() || storeFor(kind).exists(folder)) {
                return;
            }
            Optional<ScrapedMetadata> scraped = scrape(folder, kind, current);
            if (scraped.isPresent()) {
                Consumer<Path> listener = onScraped;
                notificationExecutor.execute(() -> listener.accept(folder));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Scraping " + folder + " failed");
        } finally {
            oneAtATime.release();
        }
    }

    /**
     * Built fresh per scrape from the settings of the moment. A TheTVDB login
     * token is the only thing this discards, and one login per scraped title
     * is well within the API's manners.
     */
    private Optional<ScrapedMetadata> scrape(Path folder, Kind kind, ScraperSettings current) {
        Optional<OllamaTitleService> ollama = current.ollamaConfigured()
                ? Optional.of(new OllamaTitleService(
                        current.ollamaEndpoint(), current.ollamaApiKey(), current.ollamaModel()))
                : Optional.empty();
        TvdbClient tvdb = new TvdbClient(current.tvdbApiKey().orElseThrow());
        return switch (kind) {
            case SERIES -> new SeriesScraper(
                    new SeriesEvidenceCollector(), ollama, tvdb, seriesStore, posters).scrape(folder);
            case MOVIE -> new MovieScraper(
                    new MovieEvidenceCollector(durationProbe), ollama, tvdb, movieStore, posters).scrape(folder);
        };
    }

    private ScrapedMetadataStore storeFor(Kind kind) {
        return kind == Kind.SERIES ? seriesStore : movieStore;
    }
}
