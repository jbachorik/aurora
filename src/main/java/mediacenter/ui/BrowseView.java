package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import javafx.scene.Node;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaAccessException;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.PhotoWalker;
import mediacenter.ui.components.ActionTile;
import mediacenter.ui.components.MediaTile;
import mediacenter.ui.components.Tile;
import mediacenter.ui.components.TileGrid;

/**
 * A browsable folder rendered as a tile grid.
 *
 * <p>Scanning always happens on a background thread; a folder that is slow or
 * unreachable leaves the UI fully responsive and ends in a readable message
 * rather than a frozen screen.
 */
public final class BrowseView implements View {

    /** Concurrent folder-artwork lookups; matches what the scanner used to allow. */
    private static final int ARTWORK_LOOKUP_PARALLELISM = 16;

    private final UiContext context;
    private final MediaRoot root;
    private final Path folder;
    private final TileGrid grid = new TileGrid();
    private final MediaTile.Shape shape;

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
        this.shape = root.type().usesPosterLayout() ? MediaTile.Shape.POSTER : MediaTile.Shape.WIDE;

        grid.setOnActivate(this::activate);
        load();
    }

    @Override
    public Node node() {
        return grid;
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
        grid.focusSelection();
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
        grid.showMessage("Loading…");
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

    /**
     * Looks up each folder's artwork and drops it onto its tile as it arrives.
     *
     * <p>The scan deliberately skipped these: one directory listing per folder,
     * and over a share that is the whole wait. The names are on screen already,
     * so the posters can take as long as they take — and a load that has been
     * replaced stops the moment it notices.
     */
    private void fillDirectoryArtwork(List<MediaTile> tiles, int generation) {
        if (tiles.isEmpty()) {
            return;
        }
        // The same ceiling the scanner used when it did this itself: enough to
        // keep a share busy, few enough not to bury it.
        Semaphore permits = new Semaphore(ARTWORK_LOOKUP_PARALLELISM);
        for (MediaTile tile : tiles) {
            context.backgroundExecutor().execute(() -> {
                if (generation != loadGeneration || discarded) {
                    return;
                }
                Optional<Path> artwork;
                try {
                    permits.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    artwork = context.scanner().directoryArtwork(tile.item().path());
                } finally {
                    permits.release();
                }
                artwork.ifPresent(path -> FxTasks.onFx(() -> {
                    if (generation == loadGeneration && !discarded) {
                        tile.showArtwork(path);
                    }
                }));
            });
        }
    }

    private void showItems(List<MediaItem> scanned) {
        items = scanned;
        // The load's own generation is a local of load(); the field is what is in
        // scope here, and it is the same number until the next load begins.
        int generation = loadGeneration;
        if (items.isEmpty()) {
            grid.clear();
            grid.showMessage("This folder has nothing to show.");
            // Still worth asking: a folder whose own entries are all hidden may
            // have photographs several levels below it.
            offerSlideshow(generation);
            return;
        }
        List<Tile> tiles = new ArrayList<>(items.size());
        List<MediaTile> awaitingArtwork = new ArrayList<>();
        for (MediaItem item : items) {
            MediaTile tile = new MediaTile(item, shape, context.artworkCache(), context.settings().get().theme());
            tiles.add(tile);
            if (item.isDirectory() && item.artworkPath().isEmpty()) {
                awaitingArtwork.add(tile);
            }
        }
        grid.setTiles(tiles);
        grid.focusSelection();
        fillDirectoryArtwork(awaitingArtwork, generation);
        // A photograph in this very folder settles the question with no walk at
        // all, and this is the folder a viewer of photographs is usually in. The
        // walk below is the expensive case — a shelf of films whose posters are
        // all claimed as artwork holds no photographs at any depth, so it descends
        // the entire subtree, over a share, only to answer "no".
        if (items.stream().anyMatch(MediaItem::isImage)) {
            addSlideshowTile();
            return;
        }
        offerSlideshow(generation);
    }

    /**
     * The Slideshow tile only makes sense where there are photographs, and finding
     * that out means walking the tree — so the grid is filled first and the tile
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
                    // otherwise let a stale answer repopulate the grid.
                    if (!hasPhotos || generation != loadGeneration) {
                        return;
                    }
                    addSlideshowTile();
                },
                failure -> { });
    }

    /** The one insertion path, whether the answer took a walk or came for free. */
    private void addSlideshowTile() {
        // Inserted rather than set: the grid is already on screen with the focus
        // on one of its tiles, and rebuilding it would take that tile out of the
        // scene and the highlight with it. The grid shifts the selection along by
        // one for us.
        grid.insertTile(0, new ActionTile("▣", "Slideshow", "Every photograph, including subfolders"));
        // For the empty folder, whose only tile this now is; where the grid
        // already had tiles the insertion keeps the highlight where it was, focus
        // and all.
        grid.focusSelection();
    }

    private void showFailure(Exception failure) {
        items = List.of();
        grid.clear();
        String message = failure instanceof MediaAccessException accessFailure
                ? accessFailure.userMessage()
                : "This folder could not be opened.";
        grid.showMessage(message + "\n\nPress F5 to try again.");
        context.navigation().showError(message);
    }

    private void activate(Tile tile) {
        if (tile instanceof ActionTile) {
            // The only action this grid ever holds.
            context.navigation().openSlideshow(folder);
            return;
        }
        if (!(tile instanceof MediaTile mediaTile)) {
            return;
        }
        MediaItem item = mediaTile.item();
        if (item.isDirectory()) {
            context.navigation().browse(root, item.path());
            return;
        }
        if (item.isImage()) {
            // The path, not a position: this grid and the walk that fills the
            // viewer are ordered by different code, and an index would drift.
            context.navigation().openPhoto(folder, item.path());
            return;
        }
        context.navigation().play(item);
    }
}
