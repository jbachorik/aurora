package mediacenter.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import mediacenter.config.ApplicationSettings;
import mediacenter.history.PlaybackHistoryEntry;
import mediacenter.media.DisplayNames;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.ui.components.ActionTile;
import mediacenter.ui.components.MediaTile;
import mediacenter.ui.components.Tile;
import mediacenter.ui.components.TileGrid;

/**
 * The start screen: a row of large actions and, underneath, what was played
 * recently.
 */
public final class HomeView implements View {

    private static final int RECENT_LIMIT = 12;

    private final UiContext context;
    private final VBox root = new VBox();
    private final TileGrid actions = new TileGrid();
    private final TileGrid recent = new TileGrid();
    private final Label recentHeading = new Label("Recently Played");

    public HomeView(UiContext context) {
        this.context = context;

        root.getStyleClass().add("home-view");
        root.setSpacing(8);
        root.setPadding(new Insets(8, 0, 0, 0));
        // With nothing played yet there is no second row, and the actions would
        // otherwise cling to the top of an empty page.
        root.setAlignment(Pos.CENTER);

        actions.setPrefHeight(ActionTile.HEIGHT + 56);
        actions.setMinHeight(ActionTile.HEIGHT + 56);
        // Once the tiles reach their maximum width a wide screen has room to spare,
        // and the row has to sit in the middle of it rather than against one edge.
        actions.setTileAlignment(Pos.CENTER);
        // The row is a fixed one tile tall, so it must stay one tile tall: anything
        // that wraps is both invisible and, worse, keeps Down from reaching Recents.
        actions.setFitTilesToSingleRow(true);
        actions.setOnActivate(this::activate);
        actions.setOnNavigateBelow(() -> {
            if (!recent.isEmpty()) {
                recent.select(recent.selectedIndex() < 0 ? 0 : recent.selectedIndex());
            }
        });

        recentHeading.getStyleClass().add("section-heading");
        // The page is centre-aligned for the empty case, which would otherwise
        // centre this heading too and detach it from the left-aligned row it
        // labels. Filling the width lets its own left padding do the aligning.
        recentHeading.setMaxWidth(Double.MAX_VALUE);
        // Preferred, not minimum: on a smaller window the row simply scrolls
        // instead of pushing the hint bar off the screen.
        recent.setPrefHeight(MediaTile.Shape.POSTER.totalHeight() + 56);
        recent.setOnActivate(this::activate);
        recent.setOnNavigateAbove(() -> actions.select(Math.max(actions.selectedIndex(), 0)));

        VBox.setVgrow(actions, Priority.NEVER);
        VBox.setVgrow(recent, Priority.ALWAYS);
        root.getChildren().add(actions);

        rebuildActions();
        refreshRecent();
    }

    @Override
    public Node node() {
        return root;
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
        refreshRecent();
        focusSelection();
    }

    @Override
    public void refresh() {
        rebuildActions();
        refreshRecent();
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
        tiles.add(new ActionTile("⌂", "Browser / Desktop", browserSubtitle(settings)));
        tiles.add(new ActionTile("⚙", "Settings", null));
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

    private static String browserSubtitle(ApplicationSettings settings) {
        return settings.browserPath()
                .map(path -> path.getFileName() == null ? path.toString() : path.getFileName().toString())
                .orElse("Show the desktop");
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
            case "Browse" -> context.navigation()
                    .openRoots("Browse", settings.mediaRoots());
            case "Browser / Desktop" -> context.navigation().openBrowserOrDesktop();
            case "Settings" -> context.navigation().openSettings();
            case "Exit" -> context.navigation().exitApplication();
            default -> { }
        }
    }

    // -- recently played ----------------------------------------------------

    /** Rebuilds the recent row; artwork lookup happens off the JavaFX thread. */
    private void refreshRecent() {
        List<PlaybackHistoryEntry> entries = context.history().mostRecent(RECENT_LIMIT);
        if (entries.isEmpty()) {
            root.getChildren().removeAll(recentHeading, recent);
            recent.clear();
            return;
        }
        if (!root.getChildren().contains(recent)) {
            root.getChildren().addAll(recentHeading, recent);
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
            tiles.add(new MediaTile(item, MediaTile.Shape.POSTER, context.artworkCache(),
                    context.settings().get().theme()));
        }
        recent.setTiles(tiles);
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
