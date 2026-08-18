package mediacenter.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
 *
 * <p>The grid also owns that focus: {@link #wire} refuses any focus a tile
 * receives that the grid did not itself request through {@link #select}, so
 * keyboard (Tab) traversal into or within the grid never sticks. Nothing in this
 * codebase currently relies on Tab reaching a tile, so this is a documented trade
 * rather than a regression — but a future page mixing a {@code TileGrid} with
 * another focusable control would need to revisit it.
 */
public final class TileGrid extends ScrollPane {

    private static final double SCROLL_MARGIN = 24;

    private final TilePane tilePane = new TilePane();
    private final Label messageLabel = new Label();
    private final List<Tile> tiles = new ArrayList<>();

    private Consumer<Tile> onActivate = tile -> { };
    private Consumer<Tile> onSelectionChanged = tile -> { };
    private Runnable onNavigateAbove = () -> { };
    private Runnable onNavigateBelow = () -> { };
    private int selectedIndex = -1;
    private boolean fitTilesToSingleRow;
    private final ActivationGate activationGate = new ActivationGate();

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

        // The share each tile gets depends on how much room the grid was given, so
        // a resized window has to be recomputed.
        viewportBoundsProperty().addListener((observable, was, is) -> fitTiles());

        // A filter, not a handler: ScrollPane's own skin would otherwise turn the
        // arrow keys into scrolling instead of selection movement.
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
        addEventFilter(KeyEvent.KEY_RELEASED, this::handleKeyReleased);
    }

    /**
     * Where the tiles sit inside the grid. The default puts them at the top left;
     * a single row on an otherwise empty page reads better centred.
     */
    public void setTileAlignment(Pos alignment) {
        tilePane.setAlignment(alignment);
    }

    /**
     * Keeps every tile on one row by narrowing them to share the width available.
     *
     * <p>A wrapped grid is not merely untidy: Down moves to the row beneath within
     * the grid, so a home row that wraps swallows the key that is supposed to reach
     * the row below it. Tiles that cannot resize simply stay as they are.
     */
    public void setFitTilesToSingleRow(boolean fit) {
        this.fitTilesToSingleRow = fit;
        fitTiles();
    }

    private void fitTiles() {
        if (!fitTilesToSingleRow || tiles.isEmpty()) {
            return;
        }
        double available = availableWidth();
        if (available <= 0) {
            return;
        }
        int count = tiles.size();
        // Floored: sharing the width exactly leaves the row one rounding error away
        // from wrapping, which is the whole failure being prevented here.
        double perTile = Math.floor((available - (count - 1) * tilePane.getHgap()) / count);
        for (Tile tile : tiles) {
            tile.resizeToWidth(perTile);
        }
        tilePane.setPrefTileWidth(tiles.getFirst().getPrefWidth());
        // Columns are otherwise derived from the width, which is exactly the
        // calculation being overridden here.
        tilePane.setPrefColumns(count);
    }

    /** The width inside the grid's own padding, which the tiles have to share. */
    private double availableWidth() {
        Bounds viewport = getViewportBounds();
        double width = viewport != null && viewport.getWidth() > 0 ? viewport.getWidth() : getWidth();
        Insets padding = tilePane.getPadding();
        return width - padding.getLeft() - padding.getRight();
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
        sizeCells();
        fitTiles();

        selectedIndex = tiles.isEmpty() ? -1 : Math.min(Math.max(previousIndex, 0), tiles.size() - 1);
        showMessage(null);
    }

    /**
     * Adds a single tile at a position, leaving the others exactly as they are.
     *
     * <p>Deliberately not {@link #setTiles} with a rebuilt list. Replacing the
     * children takes the focused tile out of the scene. {@code Node.focusSetDirty}
     * marks the scene focus-dirty whenever a focus-traversable node enters or
     * leaves it, and {@code Tile}'s constructor calls {@code setFocusTraversable
     * (true)}, so the next pulse runs {@code focusCleanup()} — before layout — and
     * then {@code focusInitial()} hands the focus to the first traversable node,
     * i.e. tile 0, whether or not that is the tile the viewer was looking at.
     *
     * <p>Inserting does not, by itself, avoid that pulse: a newly inserted tile is
     * also focus-traversable, so it sets the same {@code focusDirty} flag and
     * {@code focusCleanup()} still runs. This method is a no-op for focus only
     * when the selected tile happens to already be a live focus owner at the
     * moment of insertion — which is why {@code insertTile} alone was not a
     * complete fix (see the fix table in the task-11 report). The actual
     * guarantee comes from {@link #wire}'s refusal of focus it did not request,
     * which fires inside {@code FocusOwnerProperty.invalidated()} — synchronously,
     * strictly before any key event can be delivered. The race between rebuild
     * and pulse still exists; the recovery from it is what is deterministic.
     */
    public void insertTile(int index, Tile tile) {
        int at = Math.min(Math.max(index, 0), tiles.size());
        wire(tile);
        tiles.add(at, tile);
        tilePane.getChildren().add(at, tile);
        sizeCells();
        fitTiles();
        if (selectedIndex >= at) {
            // Everything from here on has moved one place along, and the selection
            // has to move with it or the highlight lands on a different tile.
            selectedIndex++;
        }
        showMessage(null);
    }

    /**
     * Sizes every cell from the largest tile, not the first: a grid may hold an
     * action tile beside media tiles, and a cell sized for the smaller clips the
     * larger.
     */
    private void sizeCells() {
        if (tiles.isEmpty()) {
            return;
        }
        tilePane.setPrefTileWidth(tiles.stream().mapToDouble(Tile::getPrefWidth).max().orElse(0));
        tilePane.setPrefTileHeight(tiles.stream().mapToDouble(Tile::getPrefHeight).max().orElse(0));
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
            if (!isFocused) {
                return;
            }
            int index = tiles.indexOf(tile);
            if (index < 0) {
                return;
            }
            if (selectedIndex >= 0 && index != selectedIndex) {
                // Nobody here asked for this. Every move the viewer makes — an
                // arrow, a click — goes through select(), which moves the selection
                // before the focus, so the two only disagree when JavaFX has moved
                // the focus on its own: it empties the focus owner during the pulse
                // whenever the node holding it has left the scene, which is every
                // change of page, and then hands the focus to the first tile it can
                // find. Following that would move the highlight — and what Enter
                // opens — without the viewer touching anything, so the selection
                // takes the focus back instead. Keyboard traversal is given up
                // with it, which this grid has never used.
                select(selectedIndex);
                return;
            }
            if (selectedIndex < 0) {
                // Nothing was selected yet, so whatever has taken the focus is it.
                selectedIndex = index;
                onSelectionChanged.accept(tile);
            }
            ensureVisible(tile);
        });
        tile.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            select(tiles.indexOf(tile));
            if (event.getClickCount() >= 2) {
                // A double click cannot auto-repeat, so it always activates.
                activationGate.rearm();
                if (activationGate.pressed(System.nanoTime())) {
                    onActivate.accept(tile);
                }
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
                int above = GridGeometry.indexInAdjacentRow(tileBounds(), current, true);
                if (above >= 0) {
                    select(above);
                } else {
                    onNavigateAbove.run();
                }
                event.consume();
            }
            case DOWN -> {
                int below = GridGeometry.indexInAdjacentRow(tileBounds(), current, false);
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
                if (activationGate.pressed(System.nanoTime())) {
                    onActivate.accept(tiles.get(current));
                }
                event.consume();
            }
            default -> { }
        }
    }

    private void handleKeyReleased(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
            activationGate.released();
        }
    }

    /** Bounds of every tile, in the order the grid holds them. */
    private List<Bounds> tileBounds() {
        return tiles.stream().map(Node::getBoundsInParent).toList();
    }

    private int stepRows(int from, int rows) {
        int index = from;
        int step = Integer.signum(rows);
        for (int i = 0; i < Math.abs(rows); i++) {
            int next = GridGeometry.indexInAdjacentRow(tileBounds(), index, step < 0);
            if (next < 0) {
                return step < 0 ? 0 : tiles.size() - 1;
            }
            index = next;
        }
        return index;
    }

    private void ensureVisible(Tile tile) {
        Bounds bounds = tile.getBoundsInParent();
        setVvalue(ScrollGeometry.vvalueFor(
                getVvalue(),
                tilePane.getBoundsInParent().getHeight(),
                getViewportBounds().getHeight(),
                bounds.getMinY(),
                bounds.getMaxY(),
                SCROLL_MARGIN));
    }
}
