package mediacenter.playback.vlc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where the libVLC shared library lives, worked out from names alone.
 *
 * <p>Separated from the loading for the usual reason: the answers can be
 * reasoned about — and tested — without opening a single library. The list is
 * ordered: wherever the viewer pointed Settings for the VLC <em>program</em>
 * is also the install whose <em>library</em> should be used, so those
 * candidates come first and the platform's conventional locations follow.
 *
 * <p>Windows and macOS need {@code libvlccore} loaded explicitly before
 * {@code libvlc}: the operating system's loader does not look in the
 * library's own directory for its dependencies the way {@code ld.so} does.
 */
public final class LibVlcLocations {

    /**
     * One place to try: an optional core library to load first, and the
     * library itself — an absolute path, or a bare name for the system loader.
     */
    public record Candidate(Optional<Path> core, String library) {

        static Candidate inDirectory(Path directory, String coreName, String libraryName) {
            return new Candidate(
                    Optional.of(directory.resolve(coreName)),
                    directory.resolve(libraryName).toString());
        }

        static Candidate named(String libraryName) {
            return new Candidate(Optional.empty(), libraryName);
        }
    }

    private LibVlcLocations() {
    }

    /**
     * The places to try, most specific first.
     *
     * @param osName        {@code System.getProperty("os.name")}
     * @param vlcExecutable the VLC program from Settings, whose install is
     *                      the library the viewer expects to be used
     */
    public static List<Candidate> candidates(String osName, Optional<Path> vlcExecutable) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return windows(vlcExecutable);
        }
        if (os.contains("mac")) {
            return mac(vlcExecutable);
        }
        return linux();
    }

    private static List<Candidate> windows(Optional<Path> vlcExecutable) {
        List<Candidate> candidates = new ArrayList<>();
        vlcExecutable.map(Path::getParent).ifPresent(directory ->
                candidates.add(Candidate.inDirectory(directory, "libvlccore.dll", "libvlc.dll")));
        candidates.add(Candidate.inDirectory(
                Path.of("C:\\Program Files\\VideoLAN\\VLC"), "libvlccore.dll", "libvlc.dll"));
        return List.copyOf(candidates);
    }

    private static List<Candidate> mac(Optional<Path> vlcExecutable) {
        List<Candidate> candidates = new ArrayList<>();
        // The configured program is .../VLC.app/Contents/MacOS/VLC; the
        // libraries live beside it in Contents/MacOS/lib.
        vlcExecutable.map(Path::getParent).ifPresent(directory ->
                candidates.add(Candidate.inDirectory(
                        directory.resolve("lib"), "libvlccore.dylib", "libvlc.dylib")));
        candidates.add(Candidate.inDirectory(
                Path.of("/Applications/VLC.app/Contents/MacOS/lib"),
                "libvlccore.dylib", "libvlc.dylib"));
        return List.copyOf(candidates);
    }

    private static List<Candidate> linux() {
        // A distribution install registers with ld.so, which also resolves
        // libvlccore on its own — bare names are the whole answer.
        return List.of(
                Candidate.named("libvlc.so.5"),
                Candidate.named("libvlc.so"));
    }
}
