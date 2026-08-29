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
 * <p>Browsing a TV shelf offers every folder on it to this service; the
 * service decides which are worth a scrape. Cheap disqualifications first —
 * scraping switched off, folder already carrying its metadata file, folder
 * already tried since startup — and only then the real work, serialised so
 * twenty new series never open twenty conversations with TheTVDB at once.
 *
 * <p>"Already tried" is remembered per run and not on disk: a folder that
 * found no confident match is left alone until the next start rather than
 * being retried on every visit, but is never marked failed forever — the
 * missing season that made it ambiguous may arrive next week.
 */
public final class SeriesScrapeService {

    private static final Logger LOG = Logger.getLogger(SeriesScrapeService.class.getName());

    private final Supplier<ScraperSettings> settings;
    private final Executor backgroundExecutor;
    private final Executor notificationExecutor;
    private final SeriesMetadataStore store = new SeriesMetadataStore();

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
     */
    public SeriesScrapeService(
            Supplier<ScraperSettings> settings,
            Executor backgroundExecutor,
            Executor notificationExecutor) {
        this.settings = settings;
        this.backgroundExecutor = backgroundExecutor;
        this.notificationExecutor = notificationExecutor;
    }

    /** Registers the one interested party, replacing the previous one. */
    public void setOnScraped(Consumer<Path> listener) {
        this.onScraped = listener == null ? folder -> { } : listener;
    }

    /**
     * Offers a folder for scraping. Returns immediately; whether anything
     * comes of it is decided in the background.
     */
    public void scrapeIfNeeded(Path seriesFolder) {
        Path folderName = seriesFolder.getFileName();
        if (folderName == null
                || SeriesEvidenceCollector.seasonNumberOf(folderName.toString()).isPresent()) {
            // A "Season 2" folder is part of a series, not one of its own; its
            // parent is the folder whose name means something.
            return;
        }
        if (!settings.get().readyToScrape() || !attempted.add(seriesFolder)) {
            return;
        }
        try {
            backgroundExecutor.execute(() -> scrapeQuietly(seriesFolder));
        } catch (RejectedExecutionException e) {
            // Shutdown; the folder will be offered again next run.
        }
    }

    private void scrapeQuietly(Path seriesFolder) {
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
            if (!current.readyToScrape() || store.exists(seriesFolder)) {
                return;
            }
            Optional<SeriesMetadata> scraped = buildScraper(current).scrape(seriesFolder);
            if (scraped.isPresent()) {
                Consumer<Path> listener = onScraped;
                notificationExecutor.execute(() -> listener.accept(seriesFolder));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Scraping " + seriesFolder + " failed");
        } finally {
            oneAtATime.release();
        }
    }

    /**
     * Built fresh per scrape from the settings of the moment. A TheTVDB login
     * token is the only thing this discards, and one login per scraped series
     * is well within the API's manners.
     */
    private SeriesScraper buildScraper(ScraperSettings current) {
        Optional<OllamaTitleService> ollama = current.ollamaConfigured()
                ? Optional.of(new OllamaTitleService(
                        current.ollamaEndpoint(), current.ollamaApiKey(), current.ollamaModel()))
                : Optional.empty();
        return new SeriesScraper(
                new SeriesEvidenceCollector(),
                ollama,
                new TvdbClient(current.tvdbApiKey().orElseThrow()),
                store);
    }
}
