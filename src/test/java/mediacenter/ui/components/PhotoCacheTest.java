package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoCacheTest {

    private static List<Path> photos(int count) {
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            paths.add(Path.of("/photos/" + i + ".jpg"));
        }
        return paths;
    }

    private static PhotoCache<String> cacheOf(List<String> evicted) {
        return new PhotoCache<>((photo, width, height) -> photo.toString(), evicted::add);
    }

    @Test
    @DisplayName("the picture on screen and its two neighbours are kept, nothing else")
    void keepsOnlyTheNeighbourhood() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());

        cache.show(photos(10), 5, List.of(4, 6), 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("what is no longer next to the viewer is let go of, not merely forgotten")
    void disposesOfWhatItEvicts() {
        List<String> evicted = new ArrayList<>();
        PhotoCache<String> cache = cacheOf(evicted);
        List<Path> photos = photos(10);

        cache.show(photos, 5, List.of(4, 6), 1920, 1080);
        cache.show(photos, 8, List.of(7, 9), 1920, 1080);

        assertEquals(3, cache.size());
        assertTrue(evicted.contains(photos.get(5).toString()),
                "expected the old middle to be disposed: " + evicted);
    }

    @Test
    @DisplayName("a photograph already decoded is not decoded again")
    void reusesWhatItAlreadyHas() {
        List<Path> loaded = new ArrayList<>();
        PhotoCache<String> cache = new PhotoCache<>((photo, width, height) -> {
            loaded.add(photo);
            return photo.toString();
        }, evicted -> { });
        List<Path> photos = photos(10);

        String first = cache.show(photos, 5, List.of(4, 6), 1920, 1080);
        String again = cache.show(photos, 5, List.of(4, 6), 1920, 1080);

        assertSame(first, again);
        assertEquals(3, loaded.size(), "the neighbourhood is loaded once: " + loaded);
    }

    @Test
    @DisplayName("a wrapping run prefetches the far end, which is genuinely next")
    void prefetchesAcrossTheWrap() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());
        List<Path> photos = photos(5);

        cache.show(photos, 0, List.of(4, 1), 1920, 1080);

        assertEquals(3, cache.size());
    }

    @Test
    @DisplayName("the ends of a run that does not wrap have one neighbour, not two")
    void copesWithTheEnds() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());
        List<Path> photos = photos(3);

        // Compared against the path's own toString: a separator is not the same
        // character on the machine this suite also runs on.
        assertEquals(photos.get(0).toString(), cache.show(photos, 0, List.of(1), 1920, 1080));
        assertEquals(2, cache.size());
    }

    @Test
    void aSinglePhotographIsItsOwnNeighbourhood() {
        PhotoCache<String> cache = cacheOf(new ArrayList<>());

        cache.show(photos(1), 0, List.of(), 1920, 1080);

        assertEquals(1, cache.size());
    }
}
