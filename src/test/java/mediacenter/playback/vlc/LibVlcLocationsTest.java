package mediacenter.playback.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LibVlcLocationsTest {

    @Test
    @DisplayName("the configured VLC install is tried before the conventional one")
    void configuredInstallComesFirstOnWindows() {
        // Forward slashes so the path parses into segments on every platform
        // this test runs on; Windows itself accepts them throughout.
        List<LibVlcLocations.Candidate> candidates = LibVlcLocations.candidates(
                "Windows 11", Optional.of(Path.of("D:/Apps/VLC/vlc.exe")));

        assertEquals(2, candidates.size());
        assertEquals(Path.of("D:/Apps/VLC/libvlccore.dll"), candidates.get(0).core().orElseThrow());
        assertEquals(Path.of("D:/Apps/VLC/libvlc.dll").toString(), candidates.get(0).library());
        assertTrue(candidates.get(1).library().contains("Program Files"));
    }

    @Test
    @DisplayName("on macOS the libraries sit in lib beside the configured binary")
    void findsTheLibDirectoryInsideTheMacBundle() {
        List<LibVlcLocations.Candidate> candidates = LibVlcLocations.candidates(
                "Mac OS X", Optional.of(Path.of("/Applications/VLC.app/Contents/MacOS/VLC")));

        assertEquals(
                Path.of("/Applications/VLC.app/Contents/MacOS/lib/libvlccore.dylib"),
                candidates.getFirst().core().orElseThrow());
        assertEquals(
                Path.of("/Applications/VLC.app/Contents/MacOS/lib/libvlc.dylib").toString(),
                candidates.getFirst().library());
    }

    @Test
    @DisplayName("Linux trusts the system loader: bare names, no core preload")
    void linuxUsesBareNames() {
        List<LibVlcLocations.Candidate> candidates =
                LibVlcLocations.candidates("Linux", Optional.empty());

        assertEquals(List.of("libvlc.so.5", "libvlc.so"),
                candidates.stream().map(LibVlcLocations.Candidate::library).toList());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.core().isEmpty()));
    }

    @Test
    @DisplayName("no configured program still leaves the conventional locations")
    void conventionalLocationsSurviveWithoutConfiguration() {
        assertEquals(1, LibVlcLocations.candidates("Windows 11", Optional.empty()).size());
        assertEquals(1, LibVlcLocations.candidates("Mac OS X", Optional.empty()).size());
    }
}
