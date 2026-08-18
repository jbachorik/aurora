package mediacenter.ui.components;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.image.Image;

/**
 * Bounded cache of thumbnail-sized artwork.
 *
 * <p>Images are requested at the size they are displayed at and are decoded on
 * JavaFX's own background loader, so neither the scan nor the UI thread ever
 * waits for a poster and full-resolution bitmaps are never retained.
 */
public final class ArtworkCache {

    /** Roughly a few screens' worth of posters. */
    public static final int DEFAULT_CAPACITY = 240;

    private final int capacity;
    private final Map<String, Image> images;

    public ArtworkCache() {
        this(DEFAULT_CAPACITY);
    }

    public ArtworkCache(int capacity) {
        this.capacity = capacity;
        this.images = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                // Do not call eldest.getValue().cancel() here. A row of MediaTiles
                // is built synchronously, and each tile's constructor starts loading
                // its artwork immediately, so a full row queues its decodes before
                // any of them finish. With an access-order map at capacity, the
                // eldest entries are the ones inserted first in that same loop,
                // i.e. tiles that are on screen, and their decodes are still in
                // flight when eviction runs. Cancelling them makes Image report an
                // error, and MediaTile hides its artwork on error, so the on-screen
                // thumbnails would go blank permanently.
                // Cancel-on-evict is only safe when the evicted value is known to be
                // unreferenced elsewhere; here it is the opposite.
                return size() > ArtworkCache.this.capacity;
            }
        };
    }

    /**
     * Returns a scaled, asynchronously loading image for a file.
     *
     * <p>Callers must handle {@link Image#isError()} — an unreachable share or a
     * broken file simply means the placeholder stays visible.
     */
    public Image load(Path file, double width, double height) {
        String key = file.toString() + "@" + Math.round(width) + "x" + Math.round(height);
        return images.computeIfAbsent(key, ignored ->
                new Image(file.toUri().toString(), width, height, true, true, true));
    }
}
