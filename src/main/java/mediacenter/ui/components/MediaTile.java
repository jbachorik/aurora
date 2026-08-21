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
 * A tile for one video file, as the home screen's recent row shows it.
 *
 * <p>The artwork sits on top of a generated placeholder, so a missing, still
 * loading or unreadable image simply leaves a readable coloured card showing.
 * Full-resolution bitmaps are never held: the cache decodes at tile size.
 */
public final class MediaTile extends Tile {

    /**
     * Tile proportions. Folder browsing is a list, so the recent row is the
     * only grid of these left, and {@link #WIDE} is what it asks for —
     * {@link #POSTER} is the shape a row with real posters in it would want,
     * and is kept for the day one has them.
     */
    public enum Shape {

        /** Cinema proportions, for a row whose items actually have posters. */
        POSTER(210, 315),

        /**
         * A landscape card. Films have posters; loose video files mostly have
         * nothing, and a tall poster of nothing is a tall coloured rectangle —
         * so the recent row spends its pixels on width, where the caption can
         * use them, instead of on height it has no picture to fill.
         */
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

    /**
     * The least picture a shrunk tile keeps. Past this the card stops reading as
     * a card at all, and a row with no room even for this is better scrolled
     * than flattened.
     */
    private static final double MIN_ARTWORK_HEIGHT = 80;

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
     * Gives the picture up, a little or a lot, so that the caption survives.
     *
     * <p>The home screen has to seat a row of actions, a heading and this row
     * inside whatever the screen is, and on a small one there is not enough for
     * all three. What used to happen then was that the row overflowed and the
     * captions fell off the bottom of the page — where, being taller than the
     * row that held them, no amount of scrolling could reach them. So the tile
     * takes the shortfall out of its picture instead, down to
     * {@link #MIN_ARTWORK_HEIGHT}, and the name stays on screen.
     */
    @Override
    public void resizeToHeight(double height) {
        double artwork = Math.clamp(height - CAPTION_HEIGHT, MIN_ARTWORK_HEIGHT, shape.artworkHeight());
        double total = artwork + CAPTION_HEIGHT;
        setPrefSize(shape.width(), total);
        setMinSize(shape.width(), total);
        setMaxSize(shape.width(), total);
        artworkArea.setPrefHeight(artwork);
        artworkArea.setMinHeight(artwork);
        artworkArea.setMaxHeight(artwork);
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
        // Bound rather than set: a short row shrinks the area after this, and
        // artwork that kept its full height would hang out below the caption.
        view.fitHeightProperty().bind(artworkArea.heightProperty());
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
