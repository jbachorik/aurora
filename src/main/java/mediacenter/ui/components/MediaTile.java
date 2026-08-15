package mediacenter.ui.components;

import java.nio.file.Path;
import java.util.Optional;

import javafx.animation.FadeTransition;
import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import mediacenter.media.MediaItem;

/**
 * A poster (or wide) tile for one directory or video file.
 *
 * <p>The artwork sits on top of a generated placeholder, so a missing, still
 * loading or unreadable image simply leaves a readable coloured card showing.
 * Full-resolution bitmaps are never held: the cache decodes at tile size.
 */
public final class MediaTile extends Tile {

    /** Tile proportions: posters for movie-style roots, wide cards elsewhere. */
    public enum Shape {

        POSTER(230, 345),
        WIDE(300, 180);

        private final double width;
        private final double artworkHeight;

        Shape(double width, double artworkHeight) {
            this.width = width;
            this.artworkHeight = artworkHeight;
        }

        public double width() {
            return width;
        }

        public double artworkHeight() {
            return artworkHeight;
        }

        /** Full tile height including the caption. */
        public double totalHeight() {
            return artworkHeight + CAPTION_HEIGHT;
        }
    }

    private static final double CAPTION_HEIGHT = 74;

    private final MediaItem item;

    public MediaTile(MediaItem item, Shape shape, ArtworkCache artworkCache) {
        this.item = item;

        getStyleClass().add("media-tile");
        setPrefSize(shape.width(), shape.totalHeight());
        setMinSize(shape.width(), shape.totalHeight());
        setMaxSize(shape.width(), shape.totalHeight());
        setAlignment(Pos.TOP_CENTER);

        getChildren().addAll(
                artworkArea(shape, artworkCache),
                caption());
    }

    public MediaItem item() {
        return item;
    }

    @Override
    public String title() {
        return item.displayName();
    }

    private StackPane artworkArea(Shape shape, ArtworkCache artworkCache) {
        StackPane placeholder = placeholder(shape);
        StackPane area = new StackPane(placeholder);
        area.getStyleClass().add("media-tile-artwork");
        area.setPrefSize(shape.width(), shape.artworkHeight());
        area.setMinSize(shape.width(), shape.artworkHeight());
        area.setMaxSize(shape.width(), shape.artworkHeight());

        Optional<Path> artworkPath = item.artworkPath();
        if (artworkPath.isPresent()) {
            Image image = artworkCache.load(artworkPath.get(), shape.width(), shape.artworkHeight());
            ImageView view = new ImageView(image);
            view.setFitWidth(shape.width());
            view.setFitHeight(shape.artworkHeight());
            view.setPreserveRatio(true);
            view.setSmooth(true);
            area.getChildren().add(view);
            revealWhenLoaded(image, placeholder, view);
        }
        return area;
    }

    /**
     * Keeps the placeholder visible while the image loads, then cross-fades the
     * artwork in over it. An image that fails to load simply leaves the
     * placeholder in place.
     *
     * <p>The listener removes itself, so a cached long-lived {@link Image} never
     * ends up holding on to discarded tiles.
     */
    private static void revealWhenLoaded(Image image, Node placeholder, Node artwork) {
        if (image.isError()) {
            artwork.setVisible(false);
            return;
        }
        if (image.getProgress() >= 1.0) {
            // Already decoded (a cache hit while re-entering a folder): showing it
            // straight away avoids a pointless flash on every navigation.
            placeholder.setVisible(false);
            return;
        }
        artwork.setOpacity(0);
        InvalidationListener[] listener = new InvalidationListener[1];
        listener[0] = observable -> {
            if (image.isError()) {
                artwork.setVisible(false);
                stopListening(image, listener[0]);
            } else if (image.getProgress() >= 1.0) {
                crossFade(placeholder, artwork);
                stopListening(image, listener[0]);
            }
        };
        image.progressProperty().addListener(listener[0]);
        image.errorProperty().addListener(listener[0]);
    }

    private static void stopListening(Image image, InvalidationListener listener) {
        image.progressProperty().removeListener(listener);
        image.errorProperty().removeListener(listener);
    }

    private static void crossFade(Node placeholder, Node artwork) {
        FadeTransition reveal = new FadeTransition(Motion.GENTLE, artwork);
        reveal.setFromValue(0);
        reveal.setToValue(1);
        reveal.setInterpolator(Motion.EASE);
        reveal.setOnFinished(event -> placeholder.setVisible(false));
        reveal.play();
    }

    /** Generated fallback: a stable colour per title plus the title itself. */
    private StackPane placeholder(Shape shape) {
        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("media-tile-placeholder");
        placeholder.setStyle(placeholderBackground(item.displayName()));

        Label symbol = new Label(item.isDirectory() ? "▤" : "▶");
        symbol.getStyleClass().add("media-tile-placeholder-symbol");
        StackPane.setAlignment(symbol, Pos.TOP_RIGHT);

        Label name = new Label(item.displayName());
        name.getStyleClass().add("media-tile-placeholder-title");
        name.setWrapText(true);
        name.setAlignment(Pos.CENTER);
        name.setMaxWidth(shape.width() - 32);

        placeholder.getChildren().addAll(name, symbol);
        return placeholder;
    }

    private Label caption() {
        Label caption = new Label(item.displayName());
        caption.getStyleClass().add("media-tile-caption");
        caption.setWrapText(true);
        caption.setAlignment(Pos.CENTER);
        caption.setPrefHeight(CAPTION_HEIGHT);
        caption.setMinHeight(CAPTION_HEIGHT);
        caption.setMaxHeight(CAPTION_HEIGHT);
        return caption;
    }

    /**
     * Deterministic colour so the same title always looks the same.
     *
     * <p>Tinted pastels rather than saturated blocks: on a light page a wall of
     * generated tiles should read as a shelf, not as a warning.
     */
    static String placeholderBackground(String title) {
        int hue = Math.floorMod(title.hashCode(), 360);
        return "-fx-background-color: linear-gradient(to bottom right,"
                + " hsb(" + hue + ", 16%, 97%),"
                + " hsb(" + ((hue + 22) % 360) + ", 32%, 86%));";
    }
}
