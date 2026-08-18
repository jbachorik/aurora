package mediacenter.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import javafx.scene.layout.VBox;

/**
 * Scrollable single-column list of {@link MediaListRow}s driven by Up/Down,
 * Enter and the mouse.
 *
 * <p>Selection is simply which row has the focus, and the list owns that focus
 * the same way {@link TileGrid} does: any focus a row receives that the list
 * did not itself request through {@link #select} is refused, so a JavaFX pulse
 * that hands the focus to "the first traversable node" never moves the
 * highlight on its own. The trade is the same too — keyboard (Tab) traversal
 * into the list is given up, which nothing in this codebase uses.
 */
public final class MediaListView extends ScrollPane {

    private static final double SCROLL_MARGIN = 16;

    /** Rows a PageUp/PageDown jumps: about one screenful of single lines. */
    private static final int PAGE_STEP = 10;

    private final VBox listPane = new VBox();
    private final Label messageLabel = new Label();
    private final List<MediaListRow> rows = new ArrayList<>();

    private Consumer<MediaListRow> onActivate = row -> { };
    private Consumer<MediaListRow> onSelectionChanged = row -> { };
    private int selectedIndex = -1;
    private final ActivationGate activationGate = new ActivationGate();

    public MediaListView() {
        getStyleClass().add("media-list");

        listPane.getStyleClass().add("media-list-pane");
        listPane.setSpacing(4);
        listPane.setPadding(new Insets(16, 24, 16, 24));
        listPane.setFillWidth(true);

        messageLabel.getStyleClass().add("grid-message");
        messageLabel.setWrapText(true);
        messageLabel.setAlignment(Pos.CENTER);
        messageLabel.setVisible(false);
        messageLabel.setManaged(false);

        StackPane content = new StackPane(listPane, messageLabel);
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

    /** Replaces the rows, keeping the selected position when it still exists. */
    public void setRows(List<MediaListRow> newRows) {
        int previousIndex = selectedIndex;
        rows.clear();
        rows.addAll(newRows);

        for (MediaListRow row : rows) {
            wire(row);
        }
        listPane.getChildren().setAll(rows);

        selectedIndex = rows.isEmpty() ? -1 : Math.min(Math.max(previousIndex, 0), rows.size() - 1);
        showMessage(null);
    }

    /**
     * Adds a single row at a position, leaving the others exactly as they are.
     *
     * <p>Deliberately not {@link #setRows} with a rebuilt list, for the reason
     * documented at length on {@code TileGrid.insertTile}: replacing the children
     * takes the focused row out of the scene and JavaFX's focus cleanup would hand
     * the focus — and with it the highlight — to row 0. Inserting keeps the
     * selected row live; {@link #wire}'s refusal of unrequested focus covers the
     * pulse-timing races that insertion alone does not.
     */
    public void insertRow(int index, MediaListRow row) {
        int at = Math.min(Math.max(index, 0), rows.size());
        wire(row);
        rows.add(at, row);
        listPane.getChildren().add(at, row);
        if (selectedIndex >= at) {
            // Everything from here on has moved one place along, and the selection
            // has to move with it or the highlight lands on a different row.
            selectedIndex++;
        }
        showMessage(null);
    }

    /** Shows a centred message instead of rows (loading, empty folder, error). */
    public void showMessage(String message) {
        boolean hasMessage = message != null && !message.isBlank();
        messageLabel.setText(hasMessage ? message : "");
        messageLabel.setVisible(hasMessage);
        messageLabel.setManaged(hasMessage);
        listPane.setVisible(!hasMessage);
        listPane.setManaged(!hasMessage);
    }

    public void clear() {
        rows.clear();
        listPane.getChildren().clear();
        selectedIndex = -1;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    // -- selection ----------------------------------------------------------

    public int selectedIndex() {
        return selectedIndex;
    }

    /** The row currently selected, empty while the list is. */
    public Optional<MediaListRow> selectedRow() {
        return selectedIndex < 0 || selectedIndex >= rows.size()
                ? Optional.empty()
                : Optional.of(rows.get(selectedIndex));
    }

    /** Selects a row and gives it the keyboard focus. */
    public void select(int index) {
        if (rows.isEmpty()) {
            return;
        }
        selectedIndex = Math.min(Math.max(index, 0), rows.size() - 1);
        MediaListRow row = rows.get(selectedIndex);
        row.requestFocus();
        ensureVisible(row);
        onSelectionChanged.accept(row);
    }

    /** Restores focus to the current selection, e.g. after returning from playback. */
    public void focusSelection() {
        if (rows.isEmpty()) {
            return;
        }
        select(selectedIndex < 0 ? 0 : selectedIndex);
    }

    public void setOnActivate(Consumer<MediaListRow> onActivate) {
        this.onActivate = onActivate == null ? row -> { } : onActivate;
    }

    /** Called on every selection move — this is what keeps the poster current. */
    public void setOnSelectionChanged(Consumer<MediaListRow> onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged == null ? row -> { } : onSelectionChanged;
    }

    // -- input --------------------------------------------------------------

    private void wire(MediaListRow row) {
        row.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) {
                return;
            }
            int index = rows.indexOf(row);
            if (index < 0) {
                return;
            }
            if (selectedIndex >= 0 && index != selectedIndex) {
                // Nobody here asked for this: every viewer-made move goes through
                // select(), which sets the index before the focus. Only JavaFX's
                // own focus cleanup moves the focus without the index, and
                // following it would move the highlight — and what Enter opens —
                // without the viewer touching anything. Take the focus back.
                select(selectedIndex);
                return;
            }
            if (selectedIndex < 0) {
                // Nothing was selected yet, so whatever has taken the focus is it.
                selectedIndex = index;
                onSelectionChanged.accept(row);
            }
            ensureVisible(row);
        });
        row.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            select(rows.indexOf(row));
            if (event.getClickCount() >= 2) {
                // A double click cannot auto-repeat, so it always activates.
                activationGate.rearm();
                if (activationGate.pressed(System.nanoTime())) {
                    onActivate.accept(row);
                }
            }
            event.consume();
        });
    }

    private void handleKeyPressed(KeyEvent event) {
        if (rows.isEmpty()) {
            return;
        }
        int current = Math.max(selectedIndex, 0);
        switch (event.getCode()) {
            case UP -> {
                select(current - 1);
                event.consume();
            }
            case DOWN -> {
                select(current + 1);
                event.consume();
            }
            case PAGE_UP -> {
                select(current - PAGE_STEP);
                event.consume();
            }
            case PAGE_DOWN -> {
                select(current + PAGE_STEP);
                event.consume();
            }
            case LEFT, RIGHT -> {
                // Nothing sideways to move to — but left unconsumed the skin
                // would read these as scrolling.
                event.consume();
            }
            case ENTER, SPACE -> {
                if (activationGate.pressed(System.nanoTime())) {
                    onActivate.accept(rows.get(current));
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

    private void ensureVisible(MediaListRow row) {
        Bounds bounds = row.getBoundsInParent();
        setVvalue(ScrollGeometry.vvalueFor(
                getVvalue(),
                listPane.getBoundsInParent().getHeight(),
                getViewportBounds().getHeight(),
                bounds.getMinY(),
                bounds.getMaxY(),
                SCROLL_MARGIN));
    }
}
