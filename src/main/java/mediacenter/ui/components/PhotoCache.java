package mediacenter.ui.components;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.image.Image;

/**
 * The photograph on screen and its neighbours, and nothing else.
 *
 * <p>Deliberately not an LRU. A full-screen photograph is around eight megabytes
 * decoded, and the machine this runs on is an old laptop: a cache that grows
 * would exhaust it within a folder.
 *
 * <p>Each is requested at the size it will be shown at, so JavaFX scales during
 * decode and a twenty-four megapixel photograph never exists at full size.
 *
 * <p>Generic over what is held so that the retention policy can be tested: a
 * JavaFX {@link Image} cannot be built without a toolkit, and the tests have
 * none. The shipping path is {@code PhotoCache<Image>}, so nothing downcasts.
 */
public final class PhotoCache<T> {

    /** Builds whatever the view will display, at the size asked for. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(Path photo, double width, double height);
    }

    private final Loader<T> loader;
    private final Consumer<T> onEvict;
    /**
     * Keyed on the size as well as the path, exactly as {@code ArtworkCache} is.
     * The box a photograph is decoded into depends on the rotation of whatever is
     * on screen, so a neighbour prefetched beside a portrait photograph would
     * otherwise be reused, upscaled, in a landscape box and never decoded again.
     */
    private final Map<String, T> held = new HashMap<>();

    private static String keyFor(Path photo, double width, double height) {
        return photo + "@" + Math.round(width) + "x" + Math.round(height);
    }

    public PhotoCache(Loader<T> loader, Consumer<T> onEvict) {
        this.loader = loader;
        this.onEvict = onEvict;
    }

    /** The real thing: a background-loading, downscaled JavaFX image. */
    public static Loader<Image> imageLoader() {
        return (photo, width, height) ->
                new Image(photo.toUri().toString(), width, height, true, true, true);
    }

    /**
     * A dropped image goes on decoding unless it is told not to, and holding an
     * arrow key down would otherwise put a dozen full-screen decodes in flight.
     */
    public static Consumer<Image> imageDisposer() {
        return Image::cancel;
    }

    /**
     * Returns the photograph at {@code index}, having made sure its neighbours are
     * on their way and everything else has been let go.
     *
     * @param neighbours indices to prefetch — the viewer knows whether the run
     *                   wraps, and this class does not
     */
    public T show(List<Path> photos, int index, List<Integer> neighbours, double width, double height) {
        Map<String, Path> wanted = new LinkedHashMap<>();
        wanted.put(keyFor(photos.get(index), width, height), photos.get(index));
        for (int neighbour : neighbours) {
            if (neighbour >= 0 && neighbour < photos.size()) {
                Path photo = photos.get(neighbour);
                wanted.putIfAbsent(keyFor(photo, width, height), photo);
            }
        }
        held.entrySet().removeIf(entry -> {
            if (wanted.containsKey(entry.getKey())) {
                return false;
            }
            onEvict.accept(entry.getValue());
            return true;
        });
        wanted.forEach((key, photo) -> held.computeIfAbsent(key, ignored -> loader.load(photo, width, height)));
        return held.get(keyFor(photos.get(index), width, height));
    }

    /** Everything held is released; for leaving the viewer. */
    public void clear() {
        held.values().forEach(onEvict);
        held.clear();
    }

    /** How many photographs are held; for tests. */
    public int size() {
        return held.size();
    }
}
