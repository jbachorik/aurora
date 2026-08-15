package mediacenter.ui.components;

import javafx.scene.layout.VBox;

/**
 * Base class for the large, focusable rectangles the whole UI is built from.
 *
 * <p>Focus is the selection: the {@code :focused} pseudo-class drives the
 * high-contrast highlight defined in the stylesheet, so there is never a
 * mismatch between "what is selected" and "what receives Enter".
 */
public abstract class Tile extends VBox {

    protected Tile() {
        getStyleClass().add("tile");
        setFocusTraversable(true);
    }

    /** Text announced in the header/status area when this tile is selected. */
    public abstract String title();
}
