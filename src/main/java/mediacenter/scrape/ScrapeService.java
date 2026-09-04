package mediacenter.scrape;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
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
 * decides which are worth a scrape. A Movies shelf is also offered whole, so
 * {@link LooseMovieOrganizer} can give bare files folders of their own before
 * the scrapes reach them. Cheap disqualifications first — scraping switched
 * off, folder already tried since startup — and only then the real work,
 * serialised so twenty new titles never open twenty conversations with
 * TheTVDB at once.
 *
 * <p>"Already tried" is remembered per run and not on disk: a series or film
 * folder that found no confident match is left alone until the next start
 * rather than being retried on every visit, but is never marked failed
 * forever — the year a rename adds may arrive next week. A series folder
 * that <em>did</em> earn a match is a different question every time it comes
 * back on screen, not a settled one: it is asked again, cheaply — one
 * directory listing, no network — whether the season shape on disk still
 * matches what was on record, and only a real change opens a new
 * conversation with the provider. A film's identity does not grow a second
 * season, so a folder holding exactly one already-identified film stays
 * settled for good; a folder sharing itself between several films instead
 * asks {@link MovieScraper} on every visit whether any of them are still
 * waiting to be told apart.
 */
public final class ScrapeService {

    private static final Logger LOG = Logger.getLogger(ScrapeService.class.getName());

    /** Which pipeline a folder goes down; the root it sits under decides. */
    private enum Kind { SERIES, MOVIE }

    private final Supplier<ScraperSettings> settings;
    private final Executor backgroundExecutor;
    private final Executor notificationExecutor;
    private final MediaDurationProbe durationProbe;
    private final BiConsumer<Path, Path> onFileMoved;
    private final ScrapedMetadataStore seriesStore = ScrapedMetadataStore.series();
    private final ScrapedMetadataStore movieStore = ScrapedMetadataStore.movies();
    private final PosterDownloader posters = new PosterDownloader();
    private final LooseMovieOrganizer organizer = new LooseMovieOrganizer();

    /** One scrape at a time; the queue is the executor's. */
    private final Semaphore oneAtATime = new Semaphore(1);

    private final Set<Path> attempted = ConcurrentHashMap.newKeySet();

    /** Shelves already tidied this run; a tidy that moved nothing needs no second look. */
    private final Set<Path> organized = ConcurrentHashMap.newKeySet();

    /**
     * Who to tell when a subject gains metadata — a folder ordinarily, or one
     * film's own video when its folder shares itself with others; the browse
     * page showing it, mostly.
     */
    private volatile BiConsumer<Path, ScrapedMetadata> onScraped = (folder, metadata) -> { };

    /** Who to tell when a shelf's loose films were foldered; that page must reload. */
    private volatile Consumer<Path> onReorganized = folder -> { };

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
     * @param onFileMoved          told about every relocated video, from and
     *                             to, so watched marks and playback history
     *                             can follow the file; called on the
     *                             background thread doing the moving
     */
    public ScrapeService(
            Supplier<ScraperSettings> settings,
            Executor backgroundExecutor,
            Executor notificationExecutor,
            MediaDurationProbe durationProbe,
            BiConsumer<Path, Path> onFileMoved) {
        this.settings = settings;
        this.backgroundExecutor = backgroundExecutor;
        this.notificationExecutor = notificationExecutor;
        this.durationProbe = durationProbe;
        this.onFileMoved = onFileMoved;
    }

    /** Registers the one interested party, replacing the previous one. */
    public void setOnScraped(BiConsumer<Path, ScrapedMetadata> listener) {
        this.onScraped = listener == null ? (folder, metadata) -> { } : listener;
    }

    /** Registers who hears about a tidied shelf, replacing the previous one. */
    public void setOnReorganized(Consumer<Path> listener) {
        this.onReorganized = listener == null ? folder -> { } : listener;
    }

    /** Offers a folder from a TV shelf. Returns immediately. */
    public void scrapeSeriesIfNeeded(Path seriesFolder) {
        offer(seriesFolder, Kind.SERIES);
    }

    /** Offers a folder from a Movies shelf. Returns immediately. */
    public void scrapeMovieIfNeeded(Path movieFolder) {
        offer(movieFolder, Kind.MOVIE);
    }

    /**
     * Offers a Movies shelf whose loose files may want folders of their own.
     * Returns immediately; the tidy runs where the scrapes run, and every
     * folder it creates is queued for identification right after.
     */
    public void organizeLooseMovies(Path folder, boolean folderIsMediaRoot) {
        if (!settings.get().readyToScrape() || !organized.add(folder)) {
            return;
        }
        try {
            backgroundExecutor.execute(() -> organizeQuietly(folder, folderIsMediaRoot));
        } catch (RejectedExecutionException e) {
            // Shutdown; the shelf will be offered again next run.
        }
    }

    private void organizeQuietly(Path folder, boolean folderIsMediaRoot) {
        try {
            oneAtATime.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<LooseMovieOrganizer.Move> moves;
        try {
            if (!settings.get().readyToScrape()) {
                return;
            }
            moves = organizer.organize(folder, folderIsMediaRoot);
            for (LooseMovieOrganizer.Move move : moves) {
                onFileMoved.accept(move.from(), move.to());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Tidying " + folder + " failed");
            return;
        } finally {
            oneAtATime.release();
        }
        if (moves.isEmpty()) {
            return;
        }
        // Outside the semaphore on purpose: each offer only queues its own
        // background task, but taking the lock it will need while still
        // holding it reads like a deadlock waiting to be introduced.
        for (LooseMovieOrganizer.Move move : moves) {
            scrapeMovieIfNeeded(move.to().getParent());
        }
        Consumer<Path> listener = onReorganized;
        notificationExecutor.execute(() -> listener.accept(folder));
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
        if (!settings.get().readyToScrape()) {
            return;
        }
        // An identified series folder is worth a fresh look every time it is
        // offered — a season may have arrived since the last one — so only a
        // folder still waiting for its first match is rationed to one try per
        // run; asking a settled one again and again costs one stat, not a
        // conversation with anybody.
        boolean rationedByAttempt = !(kind == Kind.SERIES && seriesStore.exists(folder));
        if (rationedByAttempt && !attempted.add(folder)) {
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
            // Looked at again behind the semaphore: the user may have switched
            // scraping off, or two visits may have queued this very folder
            // twice, while this one waited its turn.
            if (!current.readyToScrape()) {
                return;
            }
            if (kind == Kind.SERIES) {
                if (seriesStore.exists(folder) && !seriesNeedsAnotherLook(folder)) {
                    return;
                }
            } else if (movieStore.exists(folder)) {
                // A single-film folder fully identified has nothing left to
                // do; a folder shared between several films is MovieScraper's
                // own question, since it alone knows which of them are done.
                return;
            }
            for (Scraped one : scrape(folder, kind, current)) {
                BiConsumer<Path, ScrapedMetadata> listener = onScraped;
                notificationExecutor.execute(() -> listener.accept(one.subject(), one.metadata()));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "Scraping " + folder + " failed");
        } finally {
            oneAtATime.release();
        }
    }

    /**
     * Whether an already-identified series folder deserves the cost of asking
     * again: the season shape read off disk right now differs from what was
     * on record at the last successful scrape, and nobody has corrected the
     * file by hand since — a hand edit is left standing for good, the same
     * courtesy a folder the pipeline has never touched is shown.
     *
     * <p>A file predating this check, or one a stray write left unparsable,
     * carries no shape to compare against and is read the same way: nothing
     * confidently changed, so nothing is disturbed.
     */
    private boolean seriesNeedsAnotherLook(Path folder) {
        Optional<ScrapedMetadata> onRecord = seriesStore.load(folder);
        if (onRecord.isEmpty() || onRecord.get().diskEpisodeCount().isEmpty()) {
            return false;
        }
        if (seriesStore.handEditedSince(folder, onRecord.get())) {
            return false;
        }
        return new SeriesEvidenceCollector().collect(folder)
                .map(SeriesEvidence::totalEpisodes)
                .map(onDisk -> !onDisk.equals(onRecord.get().diskEpisodeCount().get()))
                .orElse(false);
    }

    /** One newly scraped subject — a folder, or one film's own video — paired with what was found. */
    private record Scraped(Path subject, ScrapedMetadata metadata) {
    }

    /**
     * Built fresh per scrape from the settings of the moment. A TheTVDB login
     * token is the only thing this discards, and one login per scraped title
     * is well within the API's manners.
     */
    private List<Scraped> scrape(Path folder, Kind kind, ScraperSettings current) {
        Optional<OllamaTitleService> ollama = current.ollamaConfigured()
                ? Optional.of(new OllamaTitleService(
                        current.ollamaEndpoint(), current.ollamaApiKey(), current.ollamaModel()))
                : Optional.empty();
        MetadataProvider provider = buildProvider(current);
        return switch (kind) {
            case SERIES -> new SeriesScraper(
                    new SeriesEvidenceCollector(), ollama, provider, seriesStore, posters)
                    .scrape(folder)
                    .map(metadata -> new Scraped(folder, metadata))
                    .map(List::of)
                    .orElse(List.of());
            case MOVIE -> new MovieScraper(
                    new MovieEvidenceCollector(durationProbe), ollama, provider, movieStore, posters)
                    .scrape(folder).stream()
                    .map(identified -> new Scraped(identified.subject(), identified.metadata()))
                    .toList();
        };
    }

    /**
     * Whichever database the user could get a key for; with both, TheTVDB —
     * the order {@link ScraperSettings#metadataProviderName()} promises, and
     * {@code readyToScrape} has already vouched that one key exists.
     */
    private static MetadataProvider buildProvider(ScraperSettings current) {
        return current.tvdbApiKey()
                .<MetadataProvider>map(TvdbClient::new)
                .orElseGet(() -> new TmdbClient(current.tmdbApiKey().orElseThrow()));
    }
}
