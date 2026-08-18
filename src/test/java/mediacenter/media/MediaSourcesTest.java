package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MediaSourcesTest {

    private static MediaRoot configured(String name, String path) {
        return MediaRoot.create(name, Path.of(path), MediaRootType.MOVIES);
    }

    @Test
    @DisplayName("what is plugged in is listed after what was configured")
    void listsConfiguredRootsFirst() {
        List<MediaRoot> merged = MediaSources.browsable(
                List.of(configured("Movies", "/media/Movies")),
                List.of(MediaSources.removable("KINGSTON", Path.of("/Volumes/KINGSTON"))));

        assertEquals(List.of("Movies", "KINGSTON"),
                merged.stream().map(MediaRoot::displayName).toList());
    }

    @Test
    @DisplayName("a drive that is already a configured root is not offered twice")
    void doesNotRepeatAConfiguredPath() {
        MediaRoot backup = configured("Backup", "/Volumes/BACKUP");

        List<MediaRoot> merged = MediaSources.browsable(
                List.of(backup),
                List.of(MediaSources.removable("BACKUP", Path.of("/Volumes/BACKUP"))));

        assertEquals(List.of("Backup"), merged.stream().map(MediaRoot::displayName).toList());
    }

    @Test
    @DisplayName("a mounted volume is a general root, not movies or series")
    void mountedVolumesAreGeneral() {
        MediaRoot volume = MediaSources.removable("KINGSTON", Path.of("/Volumes/KINGSTON"));

        assertEquals(MediaRootType.GENERAL, volume.type());
    }

    @Test
    @DisplayName("a volume is identified by where it is mounted, not by a fresh identifier")
    void identifiesAVolumeByItsPath() {
        MediaRoot first = MediaSources.removable("KINGSTON", Path.of("/Volumes/KINGSTON"));
        MediaRoot again = MediaSources.removable("KINGSTON", Path.of("/Volumes/KINGSTON"));

        assertEquals(first.id(), again.id());
    }

    @Test
    void withoutRemovablesTheListIsJustTheConfiguredRoots() {
        List<MediaRoot> configured = List.of(configured("Movies", "/media/Movies"));

        assertEquals(configured, MediaSources.browsable(configured, List.of()));
    }
}
