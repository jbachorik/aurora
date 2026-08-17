package mediacenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;

class ApplicationSettingsTest {

    private final MediaRoot movies =
            MediaRoot.create("Movies", Path.of("/media/Movies"), MediaRootType.MOVIES);
    private final MediaRoot tv =
            MediaRoot.create("TV", Path.of("/media/TV"), MediaRootType.TV);

    @Test
    void addsAndReplacesRootsById() {
        ApplicationSettings settings = ApplicationSettings.defaults().withRoot(movies).withRoot(tv);
        assertEquals(2, settings.mediaRoots().size());

        ApplicationSettings renamed = settings.withRoot(movies.withDisplayName("Films"));

        assertEquals(2, renamed.mediaRoots().size());
        assertEquals("Films", renamed.rootById(movies.id()).orElseThrow().displayName());
    }

    @Test
    void removesRootsById() {
        ApplicationSettings settings = ApplicationSettings.defaults().withRoot(movies).withRoot(tv);

        ApplicationSettings withoutTv = settings.withoutRoot(tv.id());

        assertEquals(List.of(movies), withoutTv.mediaRoots());
        assertTrue(withoutTv.rootById(tv.id()).isEmpty());
    }

    @Test
    void filtersRootsByType() {
        ApplicationSettings settings = ApplicationSettings.defaults().withRoot(movies).withRoot(tv);

        assertEquals(List.of(movies), settings.rootsOfType(MediaRootType.MOVIES));
        assertEquals(List.of(tv), settings.rootsOfType(MediaRootType.TV));
        assertTrue(settings.rootsOfType(MediaRootType.GENERAL).isEmpty());
    }

    @Test
    void settingsAreImmutable() {
        ApplicationSettings settings = ApplicationSettings.defaults().withRoot(movies);

        assertThrows(UnsupportedOperationException.class, () -> settings.mediaRoots().add(tv));
        assertEquals(Optional.empty(), settings.withVlcPath(Optional.of(Path.of("/usr/bin/vlc"))).browserPath());
        assertEquals(1, settings.mediaRoots().size());
    }

    @Test
    void rootTypeParsingIsLenient() {
        assertEquals(Optional.of(MediaRootType.MOVIES), MediaRootType.parse(" movies "));
        assertEquals(Optional.of(MediaRootType.TV), MediaRootType.parse("tv"));
        assertEquals(Optional.empty(), MediaRootType.parse("films"));
        assertEquals(Optional.empty(), MediaRootType.parse(null));
    }

    @Test
    @DisplayName("an interval nobody could watch is brought back to something usable")
    void clampsTheSlideshowInterval() {
        assertEquals(5, ApplicationSettings.defaults().slideshowSeconds());
        assertEquals(2, ApplicationSettings.defaults().withSlideshowSeconds(0).slideshowSeconds());
        assertEquals(60, ApplicationSettings.defaults().withSlideshowSeconds(3600).slideshowSeconds());
    }

    @Test
    @DisplayName("changing one setting leaves the interval alone")
    void carriesTheIntervalThroughOtherChanges() {
        ApplicationSettings settings = ApplicationSettings.defaults().withSlideshowSeconds(9);

        assertEquals(9, settings.withFullScreen(false).slideshowSeconds());
        assertEquals(9, settings.withTheme(Theme.LIGHT).slideshowSeconds());
    }

    @Test
    void mediaRootRejectsIncompleteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoot(" ", "Movies", Path.of("/media"), MediaRootType.MOVIES));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoot("id", " ", Path.of("/media"), MediaRootType.MOVIES));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRoot("id", "Movies", null, MediaRootType.MOVIES));
    }
}
