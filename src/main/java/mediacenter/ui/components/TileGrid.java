package mediacenter.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;

/**
 * Scrollable grid of large tiles driven by arrow keys, Enter and the mouse.
 *
 * <p>Selection is simply which tile has the focus, and movement is computed from
 * the tiles' actual laid-out positions rather than from an assumed column count,
 * so a ragged last row behaves the way a viewer expects.
 */
public final class TileGrid extends ScrollPane {

    private static final double SCROLL_MARGIN = 24;
    private static final double ROW_EPSILON = 2;

    /**
     * How long an activation stays "used up" when the key release that would
     * normally re-arm it never arrives. Long enough to swallow auto-repeat,
     * short enough that a lost release can never disable Enter for good.
     */
    private static final long ACTIVATION_REARM_NANOS = 1_500_000_000L;

    private final TilePane tilePane = new TilePane();
    private final Label messageLabel = new Label();
    private final List<Tile> tiles = new ArrayList<>();

    private Consumer<Tile> onActivate = tile -> { };
    private Consumer<Tile> onSelectionChanged = tile -> { };
    private Runnable onNavigateAbove = () -> { };
    private Runnable onNavigateBelow = () -> { };
    private int selectedIndex = -1;
    private boolean activationArmed = true;
    private long lastActivationNanos;

    public TileGrid() {
        getStyleClass().add("tile-grid");

        tilePane.getStyleClass().add("tile-grid-pane");
        tilePane.setHgap(24);
        tilePane.setVgap(24);
        tilePane.setPadding(new Insets(24));
        tilePane.setAlignment(Pos.TOP_LEFT);
        tilePane.setPrefColumns(1);

        messageLabel.getStyleClass().add("grid-message");
        messageLabel.setWrapText(true);
        messageLabel.setAlignment(Pos.CENTER);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);

        StackPane content = new StackPane(tilePane, messageLabel);
        content.setAlignment(Pos.CENTER);
        setContent(content);

        setFitToWidth(true);
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setFocusTraversable(false);

        // A filter, not a handler: ScrollPane's own skin would otherwise turn the
        // arrow keys into scrolling instead of selection movement.
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_RELEASED, this::handleKeyReleased);
    }

    // -- content ------------------------------------------------------------

    /** Replaces the tiles, keeping the selected position when it still exists. */
    public void setTiles(List<? extends Tile> newTiles) {
        int previousIndex = selectedIndex;
        tiles.clear();
        tiles.addAll(newTiles);

        for (Tile tile : tiles) {
            wire(tile);
        }
        tilePane.getChildren().setAll(tiles);
        if (!tiles.isEmpty()) {
            tilePane.setPrefTileWidth(tiles.getFirst().getPrefWidth());
            tilePane.setPrefTileHeight(tiles.getFirst().getPrefHeight());
        }

        selectedIndex = tiles.isEmpty() ? -1 : Math.min(Math.max(previousIndex, 0), tiles.size() - 1);
        showMessage(null);
    }

    /** Shows a centred message instead of tiles (loading, empty folder, error). */
    public void showMessage(String message) {
        boolean hasMessage = message != null && !message.isBlank();
        messageLabel.setText(hasMessage ? message : "");
        messageLabel.setVisible(hasMessage);
        messageLabel.setManaged(hasMessage);
        tilePane.setVisible(!hasMessage);
        tilePane.setManaged(!hasMessage);
    }

    public void clear() {
        tiles.clear();
        tilePane.getChildren().clear();
        selectedIndex = -1;
    }

    public boolean isEmpty() {
        return tiles.isEmpty();
    }

    // -- selection ----------------------------------------------------------

    public int selectedIndex() {
        return selectedIndex;
    }

    /** Selects by index without moving the keyboard focus (used to restore state). */
    public void setSelectedIndex(int index) {
        if (tiles.isEmpty()) {
            selectedIndex = -1;
        } else {
            selectedIndex = Math.min(Math.max(index, 0), tiles.size() - 1);
        }
    }

    /** Selects a tile and gives it the keyboard focus. */
    public void select(int index) {
        if (tiles.isEmpty()) {
            return;
        }
        setSelectedIndex(index);
        Tile tile = tiles.get(selectedIndex);
        tile.requestFocus();
        ensureVisible(tile);
        onSelectionChanged.accept(tile);
    }

    /** Restores focus to the current selection, e.g. after returning from playback. */
    public void focusSelection() {
        if (tiles.isEmpty()) {
            return;
        }
        select(selectedIndex < 0 ? 0 : selectedIndex);
    }

    public void setOnActivate(Consumer<Tile> onActivate) {
        this.onActivate = onActivate == null ? tile -> { } : onActivate;
    }

    /** Called when Up is pressed on the top row — lets a view chain several grids. */
    public void setOnNavigateAbove(Runnable onNavigateAbove) {
        this.onNavigateAbove = onNavigateAbove == null ? () -> { } : onNavigateAbove;
    }

    /** Called when Down is pressed on the bottom row. */
    public void setOnNavigateBelow(Runnable onNavigateBelow) {
        this.onNavigateBelow = onNavigateBelow == null ? () -> { } : onNavigateBelow;
    }

    // -- input --------------------------------------------------------------

    private void wire(Tile tile) {
        tile.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (isFocused) {
                int index = tiles.indexOf(tile);
                if (index >= 0 && index != selectedIndex) {
                    selectedIndex = index;
                    onSelectionChanged.accept(tile);
                }
                ensureVisible(tile);
            }
        });
        tile.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            select(tiles.indexOf(tile));
            if (event.getClickCount() >= 2) {
                activationArmed = true;
                activate(tile);
            }
            event.consume();
        });
    }

    private void handleKeyPressed(KeyEvent event) {
        if (tiles.isEmpty()) {
            return;
        }
        int current = Math.max(selectedIndex, 0);
        switch (event.getCode()) {
            case LEFT -> {
                select(Math.max(current - 1, 0));
                event.consume();
            }
            case RIGHT -> {
                select(Math.min(current + 1, tiles.size() - 1));
                event.consume();
            }
            case UP -> {
                int above = indexInAdjacentRow(current, true);
                if (above >= 0) {
                    select(above);
                } else {
                    onNavigateAbove.run();
                }
                event.consume();
            }
            case DOWN -> {
                int below = indexInAdjacentRow(current, false);
                if (below >= 0) {
                    select(below);
                } else {
                    onNavigateBelow.run();
                }
                event.consume();
            }
            case PAGE_UP -> {
                select(stepRows(current, -3));
                event.consume();
            }
            case PAGE_DOWN -> {
                select(stepRows(current, 3));
                event.consume();
            }
            case ENTER, SPACE -> {
                activate(tiles.get(current));
                event.consume();
            }
            default -> { }
        }
    }

    /**
     * Activates a tile, ignoring the key auto-repeat that a held Enter — or a
     * cheap remote whose button sticks — produces.
     *
     * <p>Without this, returning from playback while the key is still down
     * immediately launches the same file again.
     */
    private void activate(Tile tile) {
        long now = System.nanoTime();
        if (!activationArmed && now - lastActivationNanos < ACTIVATION_REARM_NANOS) {
            return;
        }
        activationArmed = false;
        lastActivationNanos = now;
        onActivate.accept(tile);
    }

    private void handleKeyReleased(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
            activationArmed = true;
        }
    }

    /**
     * Index of the tile directly above or below, matching horizontal position.
     *
     * @return {@code -1} when there is no such row
     */
    private int indexInAdjacentRow(int from, boolean upwards) {
        Bounds origin = tiles.get(from).getBoundsInParent();
        double originY = origin.getMinY();
        double originCenterX = origin.getCenterX();

        double targetRowY = Double.NaN;
        for (Tile tile : tiles) {
            double y = tile.getBoundsInParent().getMinY();
            boolean candidate = upwards ? y < originY - ROW_EPSILON : y > originY + ROW_EPSILON;
            if (!candidate) {
                continue;
            }
            if (Double.isNaN(targetRowY)
                    || (upwards ? y > targetRowY : y < targetRowY)) {
                targetRowY = y;
            }
        }
        if (Double.isNaN(targetRowY)) {
            return -1;
        }

        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            Bounds bounds = tiles.get(i).getBoundsInParent();
            if (Math.abs(bounds.getMinY() - targetRowY) > ROW_EPSILON) {
                continue;
            }
            double distance = Math.abs(bounds.getCenterX() - originCenterX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private int stepRows(int from, int rows) {
        int index = from;
        int step = Integer.signum(rows);
        for (int i = 0; i < Math.abs(rows); i++) {
            int next = indexInAdjacentRow(index, step < 0);
            if (next < 0) {
                return step < 0 ? 0 : tiles.size() - 1;
            }
            index = next;
        }
        return index;
    }

    private void ensureVisible(Tile tile) {
        Bounds viewport = getViewportBounds();
        double contentHeight = tilePane.getBoundsInParent().getHeight();
        double viewportHeight = viewport.getHeight();
        double scrollable = contentHeight - viewportHeight;
        if (scrollable <= 0) {
            setVvalue(0);
            return;
        }
        Bounds bounds = tile.getBoundsInParent();
        double visibleTop = getVvalue() * scrollable;
        double target = visibleTop;
        if (bounds.getMinY() - SCROLL_MARGIN < visibleTop) {
            target = bounds.getMinY() - SCROLL_MARGIN;
        } else if (bounds.getMaxY() + SCROLL_MARGIN > visibleTop + viewportHeight) {
            target = bounds.getMaxY() + SCROLL_MARGIN - viewportHeight;
        }
        setVvalue(Math.min(Math.max(target / scrollable, 0), 1));
    }
}
