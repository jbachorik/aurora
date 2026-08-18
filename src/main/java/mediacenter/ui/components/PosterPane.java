package mediacenter.ui.components;

import java.nio.file.Path;

import javafx.animation.FadeTransition;
import javafx.beans.InvalidationListener;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

/**
 * The right-hand column of a browsed folder: the selected item's poster.
 *
 * <p>Shows whatever artwork the selection has, fitted whole into the column,
 * and simply goes blank when there is none — the poster is an illustration of
 * the selected line, not a card of its own, so it earns no placeholder.
 *
 * <p>Loading is asynchronous through the shared {@link ArtworkCache}; an image
 * still decoding fades in when it arrives, and one that fails to decode leaves
 * the pane blank exactly as if there had been no artwork at all.
 */
public final class PosterPane extends StackPane {

    /** Column width. Leaves a 1920px screen about three quarters for the names. */
    public static final double WIDTH = 440;

    /** Decode size: the column's width at a poster's 2:3, never full resolution. */
    private static final double DECODE_WIDTH = 440;
    private static final double DECODE_HEIGHT = 660;

    private static final double PADDING = 24;

    private final ArtworkCache artworkCache;
    private final ImageView view = new ImageView();

    /** Detached before every swap, so a slow decode never resurrects itself. */
    private Image pendingImage;
    private InvalidationListener pendingListener;

    public PosterPane(ArtworkCache artworkCache) {
        this.artworkCache = artworkCache;

        getStyleClass().add("poster-pane");
        setPrefWidth(WIDTH);
        setMinWidth(WIDTH);
        setMaxWidth(WIDTH);

        view.setPreserveRatio(true);
        view.setSmooth(true);
        // Fitted to the pane, not the pane to the image: tall posters and wide
        // folder artwork both sit whole inside the column.
        view.fitWidthProperty().bind(widthProperty().subtract(PADDING * 2));
        view.fitHeightProperty().bind(heightProperty().subtract(PADDING * 2));
        getChildren().add(view);
    }

    /** Shows this artwork, replacing whatever the pane held. */
    public void show(Path artworkPath) {
        detachPending();
        Image image = artworkCache.load(artworkPath, DECODE_WIDTH, DECODE_HEIGHT);
        if (image.isError()) {
            view.setImage(null);
            return;
        }
        if (image.getProgress() >= 1.0) {
            // Already decoded — a cache hit while walking back over the list.
            // Showing it straight away avoids a flash on every selection move.
            view.setImage(image);
            view.setOpacity(1);
            return;
        }
        view.setImage(image);
        view.setOpacity(0);
        InvalidationListener[] listener = new InvalidationListener[1];
        listener[0] = observable -> {
            if (image.isError()) {
                stopListening(image, listener[0]);
                if (view.getImage() == image) {
                    view.setImage(null);
                }
            } else if (image.getProgress() >= 1.0) {
                stopListening(image, listener[0]);
                if (view.getImage() == image) {
                    fadeIn();
                }
            }
        };
        pendingImage = image;
        pendingListener = listener[0];
        image.progressProperty().addListener(listener[0]);
        image.errorProperty().addListener(listener[0]);
    }

    /** Empties the column, for a selection that has no poster. */
    public void clear() {
        detachPending();
        view.setImage(null);
    }

    private void fadeIn() {
        FadeTransition reveal = new FadeTransition(Motion.GENTLE, view);
        reveal.setFromValue(0);
        reveal.setToValue(1);
        reveal.setInterpolator(Motion.EASE);
        reveal.play();
    }

    /**
     * Unhooks the listener of a decode that is no longer wanted, so a cached
     * long-lived {@link Image} never ends up holding on to this pane.
     */
    private void detachPending() {
        if (pendingImage != null && pendingListener != null) {
            stopListening(pendingImage, pendingListener);
        }
        pendingImage = null;
        pendingListener = null;
    }

    private static void stopListening(Image image, InvalidationListener listener) {
        image.progressProperty().removeListener(listener);
        image.errorProperty().removeListener(listener);
    }
}
