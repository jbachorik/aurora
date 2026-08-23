package mediacenter.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import mediacenter.config.ApplicationSettings;
import mediacenter.config.Website;
import mediacenter.history.PlaybackHistoryEntry;
import mediacenter.media.DisplayNames;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.media.MediaSources;
import mediacenter.ui.components.ActionTile;
import mediacenter.ui.components.MediaTile;
import mediacenter.ui.components.ScrollGeometry;
import mediacenter.ui.components.Tile;
import mediacenter.ui.components.TileGrid;

/**
 * The start screen: a row of large actions and, underneath, what was played
 * recently.
 */
public final class HomeView implements View {

    private static final int RECENT_LIMIT = 12;

    /**
     * Landscape rather than poster-shaped. What lands here is whatever was
     * played, and a loose video file almost never has artwork beside it — so
     * the row was a line of tall coloured rectangles with a name too cramped
     * to read. A wide card is shorter, which keeps the row inside the page on
     * a small screen, and gives the caption half as much width again.
     */
    private static final MediaTile.Shape RECENT_SHAPE = MediaTile.Shape.WIDE;

    /** Room left around a row that is scrolled to, so it does not sit on the edge. */
    private static final double SCROLL_MARGIN = 24;

    private final UiContext context;
    private final VBox root = new VBox();
    private final ScrollPane scroller = new ScrollPane(root);
    private final TileGrid actions = new TileGrid();
    private final TileGrid websites = new TileGrid();
    private final TileGrid recent = new TileGrid();
    private final Label websitesHeading = new Label("Websites");
    private final Label recentHeading = new Label("Recently Played");

    /** What the website tiles stand for, in the order the grid shows them. */
    private List<Website> websiteEntries = List.of();

    public HomeView(UiContext context) {
        this.context = context;

        root.getStyleClass().add("home-view");
        root.setSpacing(8);
        root.setPadding(new Insets(8, 0, 0, 0));
        // With nothing played yet there is no second row, and the actions would
        // otherwise cling to the top of an empty page.
        root.setAlignment(Pos.CENTER);

        // Every section stands at its own full height and the page scrolls past
        // them, rather than each section scrolling inside a band of its own: three
        // bands sharing one screen leave none of them tall enough to show a whole
        // tile, and a row scrolled to inside a band that is itself below the fold
        // is a row nobody can see.
        actions.setFitHeightToContent(true);
        // Once the tiles reach their maximum width a wide screen has room to spare,
        // and the row has to sit in the middle of it rather than against one edge.
        actions.setTileAlignment(Pos.CENTER);
        // The row is a fixed one tile tall, so it must stay one tile tall: anything
        // that wraps is both invisible and, worse, keeps Down from reaching the row
        // beneath.
        actions.setFitTilesToSingleRow(true);
        actions.setOnActivate(this::activate);
        actions.setOnNavigateBelow(() -> {
            if (!websites.isEmpty()) {
                websites.focusSelection();
            } else if (!recent.isEmpty()) {
                recent.focusSelection();
            }
        });

        websitesHeading.getStyleClass().add("section-heading");
        websitesHeading.setMaxWidth(Double.MAX_VALUE);
        websites.setFitHeightToContent(true);
        websites.setTileAlignment(Pos.CENTER);
        websites.setFitTilesToSingleRow(true);
        websites.setOnActivate(this::activateWebsite);
        websites.setOnNavigateAbove(actions::focusSelection);
        websites.setOnNavigateBelow(() -> {
            if (!recent.isEmpty()) {
                recent.focusSelection();
            }
        });

        recentHeading.getStyleClass().add("section-heading");
        // The page is centre-aligned for the empty case, which would otherwise
        // centre this heading too and detach it from the left-aligned row it
        // labels. Filling the width lets its own left padding do the aligning.
        recentHeading.setMaxWidth(Double.MAX_VALUE);
        // However many rows the cards wrap into, all of them: the page carries
        // what does not fit rather than the row shortening its cards to a strip
        // of caption with the picture scrolled off the top.
        recent.setFitHeightToContent(true);
        recent.setOnActivate(this::activate);
        recent.setOnNavigateAbove(() -> {
            if (!websites.isEmpty()) {
                websites.focusSelection();
            } else {
                actions.focusSelection();
            }
        });

        // Following the focus is this view's job now: the arrow keys are taken by
        // the grids' own filters, so the scroll pane's skin never sees them and
        // would leave the focus on a row painted past the bottom of the window.
        actions.setOnSelectionChanged(this::ensureVisible);
        websites.setOnSelectionChanged(this::ensureVisible);
        recent.setOnSelectionChanged(this::ensureVisible);

        scroller.getStyleClass().add("home-scroll");
        // The grids own the focus and refuse any they did not ask for; a scroll
        // pane that can hold it too would take the ring off the tile the viewer
        // is looking at the first time the page is traversed.
        scroller.setFocusTraversable(false);
        scroller.setFitToWidth(true);
        // A page with only the actions on it is stretched to the window so that
        // the alignment above still has somewhere to centre them; a taller one
        // keeps its own height and scrolls.
        scroller.setFitToHeight(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Without this the viewport inherits the content's minimum height and the
        // pane grows past the window instead of scrolling inside it.
        scroller.setMinHeight(0);

        root.getChildren().add(actions);

        rebuildActions();
        rebuildWebsites();
        refreshRecent();
    }

    @Override
    public Node node() {
        return scroller;
    }

    /**
     * Brings a freshly selected tile into the page, section heading and all.
     *
     * <p>Measured against the whole page rather than against the grid the tile
     * belongs to, which is the point of the one scroll pane: moving from the last
     * website to the first recent card scrolls the heading above it into view too.
     */
    private void ensureVisible(Tile tile) {
        Bounds viewport = scroller.getViewportBounds();
        if (viewport == null || tile.getScene() == null) {
            return;
        }
        Bounds inPage = root.sceneToLocal(tile.localToScene(tile.getBoundsInLocal()));
        if (inPage == null) {
            return;
        }
        scroller.setVvalue(ScrollGeometry.vvalueFor(
                scroller.getVvalue(),
                root.getLayoutBounds().getHeight(),
                viewport.getHeight(),
                inPage.getMinY(),
                inPage.getMaxY(),
                SCROLL_MARGIN));
    }

    @Override
    public String title() {
        return "Media Center";
    }

    @Override
    public void focusSelection() {
        actions.focusSelection();
    }

    @Override
    public void onShown() {
        rebuildActions();
        rebuildWebsites();
        refreshRecent();
        focusSelection();
    }

    @Override
    public void refresh() {
        rebuildActions();
        rebuildWebsites();
        refreshRecent();
    }

    /**
     * Keeps the sections in their one order — actions, websites, recents —
     * showing whichever of them have anything to show right now.
     */
    private void relayout() {
        List<Node> children = new ArrayList<>();
        children.add(actions);
        if (!websites.isEmpty()) {
            children.add(websitesHeading);
            children.add(websites);
        }
        if (!recent.isEmpty()) {
            children.add(recentHeading);
            children.add(recent);
        }
        root.getChildren().setAll(children);
    }

    // -- websites ------------------------------------------------------------

    private void rebuildWebsites() {
        websiteEntries = context.settings().get().websites();
        List<Tile> tiles = new ArrayList<>(websiteEntries.size());
        for (Website website : websiteEntries) {
            tiles.add(new ActionTile("◉", website.name(), website.host()));
        }
        websites.setTiles(tiles);
        relayout();
    }

    private void activateWebsite(Tile tile) {
        int index = websites.selectedIndex();
        if (index >= 0 && index < websiteEntries.size()) {
            context.navigation().openWebsite(websiteEntries.get(index));
        }
    }

    // -- actions ------------------------------------------------------------

    private void rebuildActions() {
        ApplicationSettings settings = context.settings().get();
        List<Tile> tiles = new ArrayList<>();

        tiles.add(new ActionTile("▶", "Movies", describeRoots(settings, MediaRootType.MOVIES)));
        if (!settings.rootsOfType(MediaRootType.TV).isEmpty()) {
            tiles.add(new ActionTile("▦", "TV", describeRoots(settings, MediaRootType.TV)));
        }
        tiles.add(new ActionTile("▤", "Browse", describeAllRoots(settings)));
        tiles.add(new ActionTile("⚙", "Settings", null));
        tiles.add(new ActionTile("☾", "Sleep", null));
        tiles.add(new ActionTile("✕", "Exit", null));

        actions.setTiles(tiles);
    }

    private static String describeRoots(ApplicationSettings settings, MediaRootType type) {
        List<MediaRoot> roots = settings.rootsOfType(type);
        return switch (roots.size()) {
            case 0 -> "Not configured yet";
            case 1 -> roots.getFirst().displayName();
            default -> roots.size() + " folders";
        };
    }

    private static String describeAllRoots(ApplicationSettings settings) {
        int count = settings.mediaRoots().size();
        return count == 0 ? "No folders configured" : count + (count == 1 ? " folder" : " folders");
    }

    private void activate(Tile tile) {
        if (tile instanceof MediaTile mediaTile) {
            context.navigation().play(mediaTile.item());
            return;
        }
        ApplicationSettings settings = context.settings().get();
        switch (tile.title()) {
            case "Movies" -> context.navigation()
                    .openRoots("Movies", settings.rootsOfType(MediaRootType.MOVIES));
            case "TV" -> context.navigation()
                    .openRoots("TV", settings.rootsOfType(MediaRootType.TV));
            case "Browse" -> openBrowse(settings);
            case "Settings" -> context.navigation().openSettings();
            case "Sleep" -> context.navigation().sleepComputer();
            case "Exit" -> context.navigation().exitApplication();
            default -> { }
        }
    }

    /**
     * Browse offers whatever is plugged in as well as what was configured.
     *
     * <p>Finding out reads the filesystem, and on Windows asks the operating
     * system, so it happens off the JavaFX thread — a card reader with nothing in
     * it can take a moment to answer, and the screen must not wait for it. If the
     * question cannot be answered at all, the configured roots are still browsable.
     */
    private void openBrowse(ApplicationSettings settings) {
        List<MediaRoot> configured = settings.mediaRoots();
        FxTasks.run(
                context.backgroundExecutor(),
                () -> MediaSources.browsable(configured, context.platform().removableVolumes()),
                roots -> context.navigation().openRoots("Browse", roots),
                failure -> context.navigation().openRoots("Browse", configured));
    }

    // -- recently played ----------------------------------------------------

    /** Rebuilds the recent row; artwork lookup happens off the JavaFX thread. */
    private void refreshRecent() {
        List<PlaybackHistoryEntry> entries = context.history().mostRecent(RECENT_LIMIT);
        if (entries.isEmpty()) {
            recent.clear();
            relayout();
            return;
        }
        FxTasks.run(
                context.backgroundExecutor(),
                () -> toMediaItems(entries),
                this::showRecent,
                failure -> showRecent(toMediaItemsWithoutArtwork(entries)));
    }

    private void showRecent(List<MediaItem> items) {
        List<Tile> tiles = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            tiles.add(new MediaTile(item, RECENT_SHAPE, context.artworkCache(),
                    context.settings().get().theme()));
        }
        recent.setTiles(tiles);
        relayout();
    }

    private List<MediaItem> toMediaItems(List<PlaybackHistoryEntry> entries) {
        List<MediaItem> items = new ArrayList<>(entries.size());
        for (PlaybackHistoryEntry entry : entries) {
            Optional<java.nio.file.Path> artwork = Optional.ofNullable(entry.mediaPath().getParent())
                    .flatMap(parent -> context.artworkResolver().resolveForDirectory(parent));
            items.add(MediaItem.video(entry.mediaPath(), recentTitle(entry), artwork, 0));
        }
        return items;
    }

    private static List<MediaItem> toMediaItemsWithoutArtwork(List<PlaybackHistoryEntry> entries) {
        List<MediaItem> items = new ArrayList<>(entries.size());
        for (PlaybackHistoryEntry entry : entries) {
            items.add(MediaItem.video(entry.mediaPath(), recentTitle(entry), Optional.empty(), 0));
        }
        return items;
    }

    /**
     * Derived from the path rather than read back from the history file. The stored
     * title was normalised by whatever rules applied on the day it was played, so
     * re-deriving keeps the recent row consistent with the folder it came from —
     * and quietly tidies entries recorded before a rule changed.
     */
    private static String recentTitle(PlaybackHistoryEntry entry) {
        String derived = DisplayNames.forPath(entry.mediaPath());
        return derived.isBlank() ? entry.displayTitle() : derived;
    }
}
