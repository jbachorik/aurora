package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaAccessException;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.media.PhotoWalker;
import mediacenter.media.SeriesFolders;
import mediacenter.scrape.ScrapedMetadata;
import mediacenter.scrape.ScrapedMetadataStore;
import mediacenter.ui.components.MediaListRow;
import mediacenter.ui.components.MediaListView;
import mediacenter.ui.components.PosterPane;

/**
 * A browsable folder rendered as two columns: every entry as one readable
 * line, and the selected entry's poster beside the list.
 *
 * <p>The lines are the point — each carries its entry's {@link DisplayNames
 * display name}, a name too long for its line scrolls slowly under the
 * selection instead of being cut off, and a parent folder's name echoed at
 * the front is dropped ({@link mediacenter.media.ParentPrefixes}). The
 * on-disk truth stays a glance away in the header's subtitle, and the
 * on-disk name still governs the sort. Artwork is looked up only for the
 * selected line, which makes browsing a slow share cost one directory
 * listing at a time instead of one per visible folder.
 *
 * <p>A folder the scraper has identified reads by its real name: its line is
 * re-captioned with the scraped title — "Breaking Bad" where the disk says
 * {@code Breaking.Bad.S01-S05.COMPLETE.1080p} — the same title heads the
 * page when the folder is browsed into, and the selected folder's year and
 * synopsis appear under its poster.
 *
 * <p>A folder of episodes plays onwards: starting one video also queues the
 * ones after it in the listing, so finishing an episode rolls into the next
 * without a trip back to this page. Which folders count is
 * {@link SeriesFolders}' call — or the root's, where it is configured as TV.
 *
 * <p>A folder that turns out to hold exactly one video or photograph collapses
 * into that medium: its line re-badges itself and Enter plays the file
 * directly, because drilling in would only present a single line to press
 * Enter on again. Finding that out costs a listing per folder, so it happens
 * in the background after the names are up, and until the answer arrives
 * Enter simply browses as it always did.
 *
 * <p>Scanning always happens on a background thread; a folder that is slow or
 * unreachable leaves the UI fully responsive and ends in a readable message
 * rather than a frozen screen.
 */
public final class BrowseView implements View {

    /**
     * Concurrent peeks into sub-folders — for the selected line's artwork and
     * for the single-medium collapse alike. Enough to keep a share busy, few
     * enough not to bury it.
     */
    private static final int FOLDER_PEEK_PARALLELISM = 16;

    private final UiContext context;
    private final MediaRoot root;
    private final Path folder;
    private final BorderPane content = new BorderPane();
    private final MediaListView list = new MediaListView();
    private final PosterPane poster;
    /** Year and synopsis of the selected identified folder, under its poster. */
    private final Label overview = new Label();

    /** Stateless file readers; which one a folder answers to is the root's call. */
    private static final ScrapedMetadataStore SERIES_METADATA = ScrapedMetadataStore.series();
    private static final ScrapedMetadataStore MOVIE_METADATA = ScrapedMetadataStore.movies();

    /**
     * Folder-artwork answers already fetched, so walking the selection back over
     * a directory does not pay for the same listing twice. Only touched on the
     * JavaFX thread; emptied by every load, whose items it describes.
     */
    private final Map<Path, Optional<Path>> directoryArtwork = new HashMap<>();

    /**
     * Which folders collapse into the one medium they hold, as the background
     * sweep answers. Only touched on the JavaFX thread; emptied by every load.
     * A folder the sweep has not reached yet is simply absent, and activating
     * it browses — the collapse is a shortcut, never something to wait for.
     */
    private final Map<Path, Optional<MediaItem>> soleMediaByFolder = new HashMap<>();

    /**
     * What the scraper knows about each folder on screen, as the same sweep
     * answers. Only touched on the JavaFX thread; emptied by every load.
     */
    private final Map<Path, ScrapedMetadata> scrapedByFolder = new HashMap<>();

    /**
     * What the scraper knows about the browsed folder itself — the header's
     * title, where the disk spells a ripper's name. Only touched on the JavaFX
     * thread; cleared by every load.
     */
    private ScrapedMetadata ownMetadata;

    private List<MediaItem> items = List.of();
    /** The rows on screen, kept so the watched marks can be re-applied in place. */
    private List<MediaListRow> itemRows = List.of();
    /**
     * Read by the walker thread through the cancellation supplier below, so it is
     * volatile: a plain int would let that thread go on reading the generation it
     * saw when the walk began and never notice the load that replaced it.
     */
    private volatile int loadGeneration;
    /** Set once, when the page leaves the stack for good; see {@link #onHidden()}. */
    private volatile boolean discarded;

    public BrowseView(UiContext context, MediaRoot root, Path folder) {
        this.context = context;
        this.root = root;
        this.folder = folder;
        this.poster = new PosterPane(context.artworkCache());

        content.setCenter(list);
        // The poster with the scraper's words beneath it — hidden entirely for
        // a selection nothing is known about, so the column stays pure image.
        overview.getStyleClass().add("poster-overview");
        overview.setWrapText(true);
        overview.setMaxWidth(PosterPane.WIDTH - 48);
        overview.setPadding(new Insets(0, 24, 24, 24));
        showOverview(null);
        VBox rightColumn = new VBox(poster, overview);
        VBox.setVgrow(poster, Priority.ALWAYS);
        content.setRight(rightColumn);

        list.setOnActivate(this::activate);
        list.setOnSelectionChanged(this::showPosterFor);
        // A filter on the page, not on the list: the rows own the focus, and the
        // list's own filter only knows the keys every list has.
        content.addEventFilter(KeyEvent.KEY_PRESSED, this::handleWatchedKey);
        load();
    }

    @Override
    public Node node() {
        return content;
    }

    @Override
    public String title() {
        if (ownMetadata != null) {
            return ownMetadata.title();
        }
        return folder.equals(root.path()) ? root.displayName() : DisplayNames.forDirectory(folder);
    }

    @Override
    public String subtitle() {
        return folder.toString();
    }

    @Override
    public void focusSelection() {
        list.focusSelection();
    }

    @Override
    public void refresh() {
        load();
    }

    @Override
    public void onShown() {
        // Playback while this page was hidden may have marked videos watched —
        // cheap to re-read, the marks live in memory.
        refreshWatchedMarks();
        // Scrapes started from a page deeper in the stack may still be running;
        // now that this page is the one on screen, their answers belong here.
        if (scrapableRoot()) {
            context.scrapeService().setOnScraped(this::onFolderScraped);
            context.scrapeService().setOnReorganized(this::onFolderReorganized);
            // And the answers that landed while another page held that ear are
            // healed by asking the disk again — cheaply, only where a row is
            // still uncaptioned.
            refreshScrapedMetadata();
        }
        View.super.onShown();
    }

    @Override
    public void onHidden() {
        // The search for photographs below this folder can be a walk of the whole
        // subtree over a share. Nothing else will stop it, and a viewer who has
        // gone back is not waiting for its answer.
        discarded = true;
    }

    public Path folder() {
        return folder;
    }

    public MediaRoot root() {
        return root;
    }

    /** Re-reads the folder without blocking; late results from an older load are ignored. */
    private void load() {
        int generation = ++loadGeneration;
        directoryArtwork.clear();
        soleMediaByFolder.clear();
        scrapedByFolder.clear();
        ownMetadata = null;
        poster.clear();
        showOverview(null);
        loadOwnMetadata(generation);
        list.showMessage("Loading…");
        FxTasks.run(
                context.backgroundExecutor(),
                () -> context.scanner().scanWithoutDirectoryArtwork(folder, folder.equals(root.path())),
                scanned -> {
                    if (generation == loadGeneration) {
                        showItems(scanned);
                    }
                },
                failure -> {
                    if (generation == loadGeneration) {
                        showFailure(failure);
                    }
                });
    }

    private void showItems(List<MediaItem> scanned) {
        items = scanned;
        // The load's own generation is a local of load(); the field is what is in
        // scope here, and it is the same number until the next load begins.
        int generation = loadGeneration;
        if (items.isEmpty()) {
            itemRows = List.of();
            list.clear();
            list.showMessage("This folder has nothing to show.");
            // Still worth asking: a folder whose own entries are all hidden may
            // have photographs several levels below it.
            offerSlideshow(generation);
            return;
        }
        List<String> parentFolderNames = parentFolderNames();
        List<MediaListRow> rows = new ArrayList<>(items.size());
        List<MediaListRow> directoryRows = new ArrayList<>();
        for (MediaItem item : items) {
            MediaListRow row = MediaListRow.forItem(item, parentFolderNames);
            rows.add(row);
            if (item.isDirectory()) {
                directoryRows.add(row);
            }
            if (item.isVideo() && context.watched().isWatched(item.path())) {
                row.showWatched(true);
            }
        }
        itemRows = rows;
        list.setRows(rows);
        list.focusSelection();
        resolveSoleMedia(directoryRows, generation);
        offerFoldersForScraping();
        // A photograph in this very folder settles the question with no walk at
        // all, and this is the folder a viewer of photographs is usually in. The
        // walk below is the expensive case — a shelf of films whose posters are
        // all claimed as artwork holds no photographs at any depth, so it descends
        // the entire subtree, over a share, only to answer "no".
        if (items.stream().anyMatch(MediaItem::isImage)) {
            addSlideshowRow();
            return;
        }
        offerSlideshow(generation);
    }

    /**
     * Peeks into each sub-folder for the single medium it may collapse into —
     * and, on a TV or Movies shelf, for the metadata an earlier scrape left
     * there, whose title re-captions the row.
     *
     * <p>Each peek is a directory listing, which over a share is the whole
     * cost — so the list is on screen first and the answers re-badge rows as
     * they arrive, exactly like the poster lookups. The metadata piggybacks on
     * the same trip: one small file read beside a listing already paid for.
     * A load that has been replaced stops the moment it notices.
     */
    private void resolveSoleMedia(List<MediaListRow> directoryRows, int generation) {
        if (directoryRows.isEmpty()) {
            return;
        }
        Optional<ScrapedMetadataStore> metadataStore = metadataStore();
        Semaphore permits = new Semaphore(FOLDER_PEEK_PARALLELISM);
        for (MediaListRow row : directoryRows) {
            MediaItem folderItem = row.item().orElseThrow();
            context.backgroundExecutor().execute(() -> {
                if (generation != loadGeneration || discarded) {
                    return;
                }
                Optional<MediaItem> sole;
                Optional<ScrapedMetadata> scraped;
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    sole = context.scanner().soleMedia(folderItem.path());
                } catch (MediaAccessException e) {
                    // An unreadable folder does not collapse; opening it will
                    // explain itself in the folder's own words.
                    sole = Optional.empty();
                } finally {
                    permits.release();
                }
                scraped = metadataStore.flatMap(store -> store.load(folderItem.path()));
                Optional<MediaItem> answer = sole;
                Optional<ScrapedMetadata> metadata = scraped;
                FxTasks.onFx(() -> {
                    if (generation != loadGeneration || discarded) {
                        return;
                    }
                    soleMediaByFolder.put(folderItem.path(), answer);
                    answer.ifPresent(media -> {
                        row.showMediaSymbol(media.type());
                        // The row now stands for that one video, so it carries
                        // the video's watched mark too.
                        if (media.isVideo() && context.watched().isWatched(media.path())) {
                            row.showWatched(true);
                        }
                    });
                    metadata.ifPresent(found -> applyMetadata(row, folderItem.path(), found));
                });
            });
        }
    }

    /**
     * The browsed folder's own identity, for the header — "Breaking Bad" over
     * the episode list, where the disk spells a ripper's name. Asynchronous
     * like everything here; the header corrects itself a beat later, through
     * the same door every page change uses.
     */
    private void loadOwnMetadata(int generation) {
        Optional<ScrapedMetadataStore> store = metadataStore();
        if (store.isEmpty() || folder.equals(root.path())) {
            // A root's name describes a library; no file on disk renames it.
            return;
        }
        FxTasks.run(
                context.backgroundExecutor(),
                () -> store.get().load(folder),
                metadata -> {
                    if (generation != loadGeneration || discarded || metadata.isEmpty()) {
                        return;
                    }
                    ownMetadata = metadata.get();
                    context.navigation().refreshHeader();
                },
                failure -> { });
    }

    /**
     * Which metadata file this shelf's folders would carry — a root's
     * declaration again; a General root's folders carry none.
     */
    private Optional<ScrapedMetadataStore> metadataStore() {
        return switch (root.type()) {
            case TV -> Optional.of(SERIES_METADATA);
            case MOVIES -> Optional.of(MOVIE_METADATA);
            case GENERAL -> Optional.empty();
        };
    }

    /**
     * Puts what the scraper learned onto the screen: the row re-captioned with
     * the real title, and — when this is the selected line — the year and
     * synopsis under the poster.
     */
    private void applyMetadata(MediaListRow row, Path folderPath, ScrapedMetadata metadata) {
        scrapedByFolder.put(folderPath, metadata);
        row.showTitle(metadata.title());
        if (isSelected(folderPath)) {
            showOverview(metadata);
        }
    }

    /**
     * Fills — or empties, given null — the text under the poster: the year
     * and status on one line, the synopsis after a blank one. Unmanaged when
     * empty, so an unidentified selection gets the whole column for its image.
     */
    private void showOverview(ScrapedMetadata metadata) {
        StringBuilder text = new StringBuilder();
        if (metadata != null) {
            metadata.year().ifPresent(text::append);
            metadata.status().ifPresent(status -> {
                if (!text.isEmpty()) {
                    text.append("  ·  ");
                }
                text.append(status);
            });
            metadata.overview().ifPresent(synopsis -> {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(synopsis);
            });
        }
        boolean present = !text.isEmpty();
        overview.setText(text.toString());
        overview.setVisible(present);
        overview.setManaged(present);
    }

    /**
     * The on-disk names of this folder and everything above it, up to and
     * including the root being browsed — the folders whose names an entry may
     * pointlessly repeat. Nothing above the root counts: {@code /media} is not
     * a name anyone chose for their films.
     */
    private List<String> parentFolderNames() {
        List<String> names = new ArrayList<>();
        for (Path current = folder;
                current != null && current.startsWith(root.path());
                current = current.getParent()) {
            Path name = current.getFileName();
            names.add(name == null ? current.toString() : name.toString());
            if (current.equals(root.path())) {
                break;
            }
        }
        return names;
    }

    /**
     * The Slideshow line only makes sense where there are photographs, and finding
     * that out means walking the tree — so the list is filled first and the line
     * appears a moment later, rather than the folder waiting on the answer.
     */
    private void offerSlideshow(int generation) {
        FxTasks.run(
                context.backgroundExecutor(),
                // The walk stops as soon as this page is gone or a newer load has
                // begun. Both are read from the walker's own thread, which is why
                // the two fields behind them are volatile.
                () -> PhotoWalker.hasPhotos(folder, () -> discarded || generation != loadGeneration),
                hasPhotos -> {
                    // Guarded like every other late result: an F5, or a theme
                    // change — which refreshes every stacked page — would
                    // otherwise let a stale answer repopulate the list.
                    if (!hasPhotos || generation != loadGeneration) {
                        return;
                    }
                    addSlideshowRow();
                },
                failure -> { });
    }

    /** The one insertion path, whether the answer took a walk or came for free. */
    private void addSlideshowRow() {
        // Inserted rather than set: the list is already on screen with the focus
        // on one of its rows, and rebuilding it would take that row out of the
        // scene and the highlight with it. The list shifts the selection along by
        // one for us.
        list.insertRow(0, MediaListRow.action("▣", "Slideshow — every photograph, including subfolders"));
        // For the empty folder, whose only row this now is; where the list
        // already had rows the insertion keeps the highlight where it was, focus
        // and all.
        list.focusSelection();
    }

    private void showFailure(Exception failure) {
        items = List.of();
        itemRows = List.of();
        list.clear();
        poster.clear();
        String message = failure instanceof MediaAccessException accessFailure
                ? accessFailure.userMessage()
                : "This folder could not be opened.";
        list.showMessage(message + "\n\nPress F5 to try again.");
        context.navigation().showError(message);
    }

    // -- poster -------------------------------------------------------------

    /**
     * Keeps the right-hand column showing the selected line's poster.
     *
     * <p>A directory's artwork costs a directory listing to find, which over a
     * share is slow enough that it is fetched only when its line is actually
     * selected — and off the JavaFX thread, with the answer remembered, so
     * holding Down never queues work for folders the selection has already left.
     */
    private void showPosterFor(MediaListRow row) {
        MediaItem item = row.item().orElse(null);
        if (item == null) {
            // The slideshow line illustrates the whole folder, not one entry.
            poster.clear();
            showOverview(null);
            return;
        }
        // The scraper's words follow the selection the way the poster does;
        // a line nothing is known about gets the whole column for its image.
        showOverview(scrapedByFolder.get(item.path()));
        if (item.artworkPath().isPresent()) {
            poster.show(item.artworkPath().get());
            return;
        }
        if (!item.isDirectory()) {
            poster.clear();
            return;
        }
        Optional<Path> known = directoryArtwork.get(item.path());
        if (known != null) {
            known.ifPresentOrElse(poster::show, poster::clear);
            return;
        }
        poster.clear();
        int generation = loadGeneration;
        FxTasks.run(
                context.backgroundExecutor(),
                () -> context.scanner().directoryArtwork(item.path()),
                artwork -> {
                    if (generation != loadGeneration || discarded) {
                        return;
                    }
                    directoryArtwork.put(item.path(), artwork);
                    // The selection may long since have moved on; only the line
                    // still selected gets to fill the column.
                    if (isSelected(item)) {
                        artwork.ifPresent(poster::show);
                    }
                },
                failure -> {
                    // A listing that failed is not an answer worth remembering:
                    // the share may simply have been busy, and re-selecting the
                    // line asks again.
                });
    }

    private boolean isSelected(MediaItem item) {
        return isSelected(item.path());
    }

    private boolean isSelected(Path path) {
        return list.selectedRow()
                .flatMap(MediaListRow::item)
                .map(selected -> selected.path().equals(path))
                .orElse(false);
    }

    // -- series scraping ------------------------------------------------------

    /**
     * Under a TV or Movies root, every folder on screen is offered to the
     * scraper — as a series or as a film, because the root has said out loud
     * what its folders hold, exactly the declaration {@link #playsOnwards}
     * already trusts. A Movies folder is offered whole as well, in case loose
     * files on it want folders of their own. Offering is all this does: the
     * service is the one that knows whether scraping is even switched on, and
     * a folder already carrying its metadata file costs nothing.
     */
    private void offerFoldersForScraping() {
        if (!scrapableRoot()) {
            return;
        }
        context.scrapeService().setOnScraped(this::onFolderScraped);
        context.scrapeService().setOnReorganized(this::onFolderReorganized);
        if (root.type() == MediaRootType.MOVIES) {
            context.scrapeService().organizeLooseMovies(folder, folder.equals(root.path()));
        }
        for (MediaItem item : items) {
            if (!item.isDirectory()) {
                continue;
            }
            if (root.type() == MediaRootType.TV) {
                context.scrapeService().scrapeSeriesIfNeeded(item.path());
            } else {
                context.scrapeService().scrapeMovieIfNeeded(item.path());
            }
        }
    }

    /** The roots whose folders have declared what they are; General has not. */
    private boolean scrapableRoot() {
        return root.type() == MediaRootType.TV || root.type() == MediaRootType.MOVIES;
    }

    /**
     * Re-captions rows whose folders were identified while this page was not
     * the listener — a scrape finishing while the viewer is inside another
     * folder tells that page, not this one, and coming back does not reload.
     * One small file read per still-uncaptioned row, off the JavaFX thread.
     */
    private void refreshScrapedMetadata() {
        Optional<ScrapedMetadataStore> store = metadataStore();
        if (store.isEmpty()) {
            return;
        }
        if (ownMetadata == null) {
            loadOwnMetadata(loadGeneration);
        }
        List<MediaListRow> uncaptioned = new ArrayList<>();
        for (MediaListRow row : itemRows) {
            MediaItem item = row.item().orElse(null);
            if (item != null && item.isDirectory() && !scrapedByFolder.containsKey(item.path())) {
                uncaptioned.add(row);
            }
        }
        if (uncaptioned.isEmpty()) {
            return;
        }
        int generation = loadGeneration;
        context.backgroundExecutor().execute(() -> {
            for (MediaListRow row : uncaptioned) {
                if (generation != loadGeneration || discarded) {
                    return;
                }
                MediaItem item = row.item().orElseThrow();
                store.get().load(item.path()).ifPresent(metadata -> FxTasks.onFx(() -> {
                    if (generation == loadGeneration && !discarded) {
                        applyMetadata(row, item.path(), metadata);
                    }
                }));
            }
        });
    }

    /**
     * The shelf on screen was tidied under the page's feet — loose files
     * became folders — so what the rows show is no longer what is there.
     * A full reload, exactly as if F5 had been pressed; it happens at most
     * once per folder per run, and only when something actually moved.
     */
    private void onFolderReorganized(Path reorganizedFolder) {
        if (!discarded && folder.equals(reorganizedFolder)) {
            load();
        }
    }

    /**
     * A scrape may have just put a poster where this page remembered "none":
     * the remembered answer goes, and the line — if it is the selected one —
     * asks again and fills the column. The banner says what was found — the
     * one place the whole feature is visible before the posters arrive, and
     * the place a wrong identification is caught while the folder it names
     * is still on screen.
     */
    private void onFolderScraped(Path scrapedFolder, ScrapedMetadata metadata) {
        if (discarded) {
            return;
        }
        if (folder.equals(scrapedFolder)) {
            // The page being browsed was itself just identified: the header is
            // where its name lives.
            ownMetadata = metadata;
            context.navigation().refreshHeader();
        }
        directoryArtwork.remove(scrapedFolder);
        // The freshly identified folder earns its caption and synopsis at
        // once, not on the next visit.
        for (MediaListRow row : itemRows) {
            MediaItem item = row.item().orElse(null);
            if (item != null && item.path().equals(scrapedFolder)) {
                applyMetadata(row, scrapedFolder, metadata);
                break;
            }
        }
        list.selectedRow().ifPresent(row -> {
            MediaItem item = row.item().orElse(null);
            if (item != null && item.path().equals(scrapedFolder)) {
                showPosterFor(row);
            }
        });
        context.navigation().showInfo("Identified \"" + metadata.title() + "\""
                + metadata.year().map(year -> " (" + year + ")").orElse(""));
    }

    // -- activation ---------------------------------------------------------

    private void activate(MediaListRow row) {
        MediaItem item = row.item().orElse(null);
        if (item == null) {
            // The only action this list ever holds.
            context.navigation().openSlideshow(folder);
            return;
        }
        if (item.isDirectory()) {
            Optional<MediaItem> sole = soleMediaByFolder.get(item.path());
            if (sole != null && sole.isPresent()) {
                // The folder holds exactly one medium, so the folder is the
                // medium: straight to it, no page with a single line in it.
                openMedia(sole.get(), item.path(), List.of());
                return;
            }
            context.navigation().browse(root, item.path());
            return;
        }
        openMedia(item, folder, playOnwardsAfter(item));
    }

    /** Opens one medium: photographs to the viewer, everything else to playback. */
    private void openMedia(MediaItem media, Path containingFolder, List<MediaItem> playOnwards) {
        if (media.isImage()) {
            // The path, not a position: this list and the walk that fills the
            // viewer are ordered by different code, and an index would drift.
            context.navigation().openPhoto(containingFolder, media.path());
            return;
        }
        context.navigation().play(media, playOnwards);
    }

    /**
     * The videos queued to follow this one — the rest of the episodes, in
     * listing order — or nothing where this folder does not read as a series.
     */
    private List<MediaItem> playOnwardsAfter(MediaItem video) {
        List<MediaItem> videos = items.stream().filter(MediaItem::isVideo).toList();
        int at = videos.indexOf(video);
        if (at < 0 || at == videos.size() - 1 || !playsOnwards(videos)) {
            return List.of();
        }
        return List.copyOf(videos.subList(at + 1, videos.size()));
    }

    /**
     * A TV root has said out loud what its folders hold; everywhere else the
     * names have to make the case themselves.
     */
    private boolean playsOnwards(List<MediaItem> videos) {
        if (videos.size() < 2) {
            return false;
        }
        return root.type() == MediaRootType.TV
                || SeriesFolders.looksLikeEpisodes(videos.stream()
                        .map(BrowseView::fileNameOf)
                        .toList());
    }

    private static String fileNameOf(MediaItem item) {
        Path name = item.path().getFileName();
        return name == null ? item.displayName() : name.toString();
    }

    // -- watched marks ------------------------------------------------------

    /**
     * W on a video flips its watched mark; W on a folder clears the marks from
     * everything inside it, subfolders and all. One key for both, because on
     * the sofa there is no room for two: the row under the highlight says
     * which of the two can be meant.
     */
    private void handleWatchedKey(KeyEvent event) {
        if (event.getCode() != KeyCode.W) {
            return;
        }
        event.consume();
        MediaListRow row = list.selectedRow().orElse(null);
        MediaItem item = row == null ? null : row.item().orElse(null);
        if (item == null) {
            return;
        }
        if (item.isVideo()) {
            row.showWatched(context.watched().toggleWatched(item.path()));
            return;
        }
        if (item.isDirectory()) {
            resetWatchedBelow(item);
        }
    }

    /** Clears every watched mark under a folder and re-badges the visible rows. */
    private void resetWatchedBelow(MediaItem folderItem) {
        if (context.watched().resetBelow(folderItem.path())) {
            refreshWatchedMarks();
            context.navigation().showInfo(
                    "Watched marks cleared for \"" + folderItem.displayName() + "\" and its subfolders.");
        } else {
            context.navigation().showInfo(
                    "Nothing inside \"" + folderItem.displayName() + "\" was marked as watched.");
        }
    }

    /**
     * Re-reads every row's watched mark from the shared state — after a reset,
     * or when the page comes back from behind a playback that marked things.
     * A folder row shows the mark of the one video it collapsed into, where
     * the background sweep has answered.
     */
    private void refreshWatchedMarks() {
        for (MediaListRow row : itemRows) {
            MediaItem item = row.item().orElse(null);
            if (item == null) {
                continue;
            }
            if (item.isVideo()) {
                row.showWatched(context.watched().isWatched(item.path()));
                continue;
            }
            Optional<MediaItem> sole = item.isDirectory() ? soleMediaByFolder.get(item.path()) : null;
            if (sole != null && sole.isPresent() && sole.get().isVideo()) {
                row.showWatched(context.watched().isWatched(sole.get().path()));
            }
        }
    }
}
