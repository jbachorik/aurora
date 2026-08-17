package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    private final UiContext context;
    private final MediaRoot root;
    private final Path folder;
    private final TileGrid grid = new TileGrid();
    private final MediaTile.Shape shape;

    private List<MediaItem> items = List.of();
    private int loadGeneration;

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
                () -> context.scanner().scan(folder, folder.equals(root.path())),
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
            grid.clear();
            grid.showMessage("This folder has nothing to show.");
            // Still worth asking: a folder whose own entries are all hidden may
            // have photographs several levels below it.
            offerSlideshow(generation);
            return;
        }
        List<Tile> tiles = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            tiles.add(new MediaTile(item, shape, context.artworkCache(), context.settings().get().theme()));
        }
        grid.setTiles(tiles);
        grid.focusSelection();
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
                () -> PhotoWalker.hasPhotos(folder),
                hasPhotos -> {
                    // Guarded like every other late result: an F5, or a theme
                    // change — which refreshes every stacked page — would
                    // otherwise let a stale answer repopulate the grid.
                    if (!hasPhotos || generation != loadGeneration) {
                        return;
                    }
                    // Inserted rather than set: the grid is already on screen with
                    // the focus on one of its tiles, and rebuilding it would take
                    // that tile out of the scene and the highlight with it. The
                    // grid shifts the selection along by one for us.
                    grid.insertTile(0,
                            new ActionTile("▣", "Slideshow", "Every photograph, including subfolders"));
                    // For the empty folder, whose only tile this now is; where the
                    // grid already had tiles the insertion keeps the highlight
                    // where it was, focus and all.
                    grid.focusSelection();
                },
                failure -> { });
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
