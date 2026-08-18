package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.scene.Node;
import javafx.scene.layout.BorderPane;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaAccessException;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.PhotoWalker;
import mediacenter.ui.components.MediaListRow;
import mediacenter.ui.components.MediaListView;
import mediacenter.ui.components.PosterPane;

/**
 * A browsable folder rendered as two columns: every entry as one line showing
 * its actual on-disk name, and the selected entry's poster beside the list.
 *
 * <p>The lines are the point — a name too long for its line scrolls slowly
 * under the selection instead of being cut off, so the real file name is
 * always readable. Artwork is looked up only for the selected line, which
 * makes browsing a slow share cost one directory listing at a time instead of
 * one per visible folder.
 *
 * <p>Scanning always happens on a background thread; a folder that is slow or
 * unreachable leaves the UI fully responsive and ends in a readable message
 * rather than a frozen screen.
 */
public final class BrowseView implements View {

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
        List<MediaListRow> rows = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            rows.add(MediaListRow.forItem(item));
        }
        list.setRows(rows);
        list.focusSelection();
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
            context.navigation().browse(root, item.path());
            return;
        }
        if (item.isImage()) {
            // The path, not a position: this list and the walk that fills the
            // viewer are ordered by different code, and an index would drift.
            context.navigation().openPhoto(folder, item.path());
            return;
        }
        context.navigation().play(item);
    }
}
