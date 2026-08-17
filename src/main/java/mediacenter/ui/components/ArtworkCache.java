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
                if (size() <= ArtworkCache.this.capacity) {
                    return false;
                }
                // The entry handed to this method is the one about to be dropped,
                // and nothing else is touched. A dropped image goes on decoding
                // unless it is told not to: opening a folder of five hundred
                // photographs starts five hundred decodes, and the ones evicted on
                // the way would otherwise keep reading the share for thumbnails no
                // longer held. Harmless on an image already loaded — cancel only
                // affects one still in flight — and the tiles on screen are the
                // most recently used, which is the far end of this queue.
                eldest.getValue().cancel();
                return true;
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
