package mediacenter.ui.components;

import javafx.animation.ScaleTransition;
import javafx.scene.layout.VBox;

/**
 * Base class for the large, focusable rectangles the whole UI is built from.
 *
 * <p>Focus is the selection: the {@code :focused} pseudo-class drives the
 * high-contrast highlight defined in the stylesheet, so there is never a
 * mismatch between "what is selected" and "what receives Enter".
 */
public abstract class Tile extends VBox {

    /** Small enough to read as a lift rather than a jump. */
    private static final double FOCUS_SCALE = 1.04;

    private ScaleTransition focusAnimation;

    protected Tile() {
        getStyleClass().add("tile");
        setFocusTraversable(true);
        focusedProperty().addListener((observable, wasFocused, isFocused) -> animateFocus(isFocused));
    }

    /** Text announced in the header/status area when this tile is selected. */
    public abstract String title();

    /**
     * Offered the width a single-row grid can spare. Tiles whose size is fixed by
     * their content — media posters — ignore it; the home actions use it to keep
     * every action on one row whatever the screen.
     */
    public void resizeToWidth(double width) {
        // Fixed-size tiles have nothing to do here.
    }

    /**
     * Lifts the focused tile slightly above its neighbours.
     *
     * <p>Scaling is a render-time transform, so the grid never reflows. It does
     * grow the tile's bounds, though — which is why {@link GridGeometry} tells rows
     * apart by their centres, the one measurement scaling about the centre leaves
     * untouched.
     */
    private void animateFocus(boolean focused) {
        setViewOrder(focused ? -1 : 0);
        if (focusAnimation != null) {
            focusAnimation.stop();
        }
        focusAnimation = new ScaleTransition(Motion.QUICK, this);
        focusAnimation.setToX(focused ? FOCUS_SCALE : 1);
        focusAnimation.setToY(focused ? FOCUS_SCALE : 1);
        focusAnimation.setInterpolator(Motion.EASE);
        focusAnimation.play();
    }
}
