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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import mediacenter.config.Theme;
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

        POSTER(210, 315),
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

    /** Two lines of a 28px caption, which is what reads from across a room. */
    private static final double CAPTION_HEIGHT = 92;

    /** Matches the tile radius less its border, so the two curves sit concentric. */
    private static final double ARTWORK_CORNER = 11;

    private final MediaItem item;
    private final Theme theme;
    private final Shape shape;
    private final ArtworkCache artworkCache;

    /** Kept so artwork that arrives after the tile is on screen has somewhere to go. */
    private StackPane artworkArea;
    private StackPane placeholder;

    public MediaTile(MediaItem item, Shape shape, ArtworkCache artworkCache, Theme theme) {
        this.item = item;
        this.theme = theme;
        this.shape = shape;
        this.artworkCache = artworkCache;

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

    /**
     * The artwork area takes its width from the tile rather than naming it: the
     * tile's border insets the content box, so anything pinned to the full tile
     * width renders out over the rounded edge.
     */
    private StackPane artworkArea(Shape shape, ArtworkCache artworkCache) {
        placeholder = placeholder();
        StackPane area = new StackPane(placeholder);
        area.getStyleClass().add("media-tile-artwork");
        area.setPrefHeight(shape.artworkHeight());
        area.setMinHeight(shape.artworkHeight());
        area.setMaxHeight(shape.artworkHeight());
        roundTopCorners(area);
        artworkArea = area;

        item.artworkPath().ifPresent(this::showArtwork);
        return area;
    }

    /**
     * Puts artwork on a tile that is already on screen.
     *
     * <p>Artwork for a folder costs a directory listing to find, which over a
     * share is slow enough that the grid is drawn without it and the posters are
     * filled in as the answers come back. The tile fades from its placeholder
     * exactly as it would have done had the path been known all along.
     */
    public void showArtwork(Path artworkPath) {
        if (artworkArea == null) {
            return;
        }
        Image image = artworkCache.load(artworkPath, shape.width(), shape.artworkHeight());
        ImageView view = new ImageView(image);
        view.fitWidthProperty().bind(artworkArea.widthProperty());
        view.setFitHeight(shape.artworkHeight());
        view.setPreserveRatio(true);
        view.setSmooth(true);
        artworkArea.getChildren().add(view);
        revealWhenLoaded(image, placeholder, view);
    }

    /**
     * The placeholder gets its rounded top from the stylesheet, but a poster is a
     * square-cornered rectangle and would square the tile off again once artwork
     * loads. Clipping the area covers both.
     */
    private static void roundTopCorners(Region area) {
        Rectangle clip = new Rectangle();
        clip.setArcWidth(ARTWORK_CORNER * 2);
        clip.setArcHeight(ARTWORK_CORNER * 2);
        clip.widthProperty().bind(area.widthProperty());
        // Overshoot the bottom so only the top corners are rounded; the caption
        // below carries the tile's lower edge.
        clip.heightProperty().bind(area.heightProperty().add(ARTWORK_CORNER));
        area.setClip(clip);
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

    /**
     * Generated fallback: a stable colour per title, and the symbol standing in for
     * the poster. The name is deliberately not repeated here — the caption below
     * already carries it, at a size that reads from a sofa.
     */
    private StackPane placeholder() {
        StackPane placeholder = new StackPane();
        placeholder.getStyleClass().add("media-tile-placeholder");
        placeholder.setStyle(PlaceholderColors.backgroundFor(item.displayName(), theme));

        Label symbol = new Label(switch (item.type()) {
            case DIRECTORY -> "▤";
            case IMAGE -> "▣";
            case VIDEO -> "▶";
        });
        symbol.getStyleClass().add("media-tile-placeholder-symbol");
        StackPane.setAlignment(symbol, Pos.CENTER);

        placeholder.getChildren().add(symbol);
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

}
