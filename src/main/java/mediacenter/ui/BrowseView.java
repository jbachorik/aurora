package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import javafx.scene.Node;
import javafx.scene.layout.BorderPane;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaAccessException;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.media.PhotoWalker;
import mediacenter.media.SeriesFolders;
import mediacenter.ui.components.MediaListRow;
import mediacenter.ui.components.MediaListView;
import mediacenter.ui.components.PosterPane;

/**
 * A browsable folder rendered as two columns: every entry as one line showing
 * its actual on-disk name, and the selected entry's poster beside the list.
 *
 * <p>The lines are the point — a name too long for its line scrolls slowly
 * under the selection instead of being cut off, so the real file name is
 * always readable. The one thing a line does not repeat is a parent folder's
 * name echoed at its front; {@link mediacenter.media.ParentPrefixes} drops it. Artwork is looked up only for the selected line, which
 * makes browsing a slow share cost one directory listing at a time instead of
 * one per visible folder.
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

    private List<MediaItem> items = List.of();
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
        content.setRight(poster);

        list.setOnActivate(this::activate);
        list.setOnSelectionChanged(this::showPosterFor);
        load();
    }

    @Override
    public Node node() {
        return content;
    }

    @Override
    public String title() {
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
        poster.clear();
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
        }
        list.setRows(rows);
        list.focusSelection();
        resolveSoleMedia(directoryRows, generation);
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
     * Peeks into each sub-folder for the single medium it may collapse into.
     *
     * <p>Each peek is a directory listing, which over a share is the whole
     * cost — so the list is on screen first and the answers re-badge rows as
     * they arrive, exactly like the poster lookups. A load that has been
     * replaced stops the moment it notices.
     */
    private void resolveSoleMedia(List<MediaListRow> directoryRows, int generation) {
        if (directoryRows.isEmpty()) {
            return;
        }
        Semaphore permits = new Semaphore(FOLDER_PEEK_PARALLELISM);
        for (MediaListRow row : directoryRows) {
            MediaItem folderItem = row.item().orElseThrow();
            context.backgroundExecutor().execute(() -> {
                if (generation != loadGeneration || discarded) {
                    return;
                }
                Optional<MediaItem> sole;
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
                Optional<MediaItem> answer = sole;
                FxTasks.onFx(() -> {
                    if (generation != loadGeneration || discarded) {
                        return;
                    }
                    soleMediaByFolder.put(folderItem.path(), answer);
                    answer.ifPresent(media -> row.showMediaSymbol(media.type()));
                });
            });
        }
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
            return;
        }
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
        return list.selectedRow()
                .flatMap(MediaListRow::item)
                .map(selected -> selected.path().equals(item.path()))
                .orElse(false);
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
}
