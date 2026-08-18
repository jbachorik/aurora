package mediacenter.playback.vlc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The loaded libVLC library and its one {@code libvlc_instance_t}.
 *
 * <p>Loaded lazily on the first embedded playback and then kept for the life
 * of the process: a native library cannot meaningfully be unloaded from under
 * players, and one instance serves any number of them in turn. If the viewer
 * repoints Settings at a different VLC install, the library already loaded
 * keeps being used until the application is restarted — the same rule every
 * process-wide native binding lives by.
 */
public final class VlcEngine {

    private static final Logger LOG = Logger.getLogger(VlcEngine.class.getName());

    /**
     * Extra arguments for {@code libvlc_new}, whitespace-separated — a
     * diagnostic hook (e.g. {@code --aout=dummy} on a machine with no audio
     * device), not a supported setting.
     */
    static final String EXTRA_ARGS_PROPERTY = "mediacenter.libvlc.args";

    /** Holds the library and the instance for the rest of the process. */
    private static final Arena ENGINE_ARENA = Arena.ofShared();

    private static VlcEngine loaded;
    private static boolean loadFailed;

    private final LibVlc lib;
    private final MemorySegment instance;

    private VlcEngine(LibVlc lib, MemorySegment instance) {
        this.lib = lib;
        this.instance = instance;
    }

    /**
     * The engine, loading it on first use.
     *
     * <p>A failed load is remembered too: probing the filesystem for a library
     * that is not there once per Enter press would only add a stutter to the
     * external-player fallback that is about to happen anyway.
     */
    public static synchronized Optional<VlcEngine> load(Optional<Path> vlcExecutable) {
        if (loaded != null) {
            return Optional.of(loaded);
        }
        if (loadFailed) {
            return Optional.empty();
        }
        Optional<LibVlc> lib = LibVlc.load(
                LibVlcLocations.candidates(System.getProperty("os.name"), vlcExecutable),
                ENGINE_ARENA);
        if (lib.isEmpty()) {
            LOG.warning("libVLC could not be loaded; embedded playback is unavailable");
            loadFailed = true;
            return Optional.empty();
        }
        MemorySegment instance = lib.get().newInstance(arguments(), ENGINE_ARENA);
        if (instance == null || instance.equals(MemorySegment.NULL)) {
            LOG.warning("libvlc_new refused to start; embedded playback is unavailable");
            loadFailed = true;
            return Optional.empty();
        }
        loaded = new VlcEngine(lib.get(), instance);
        return Optional.of(loaded);
    }

    private static List<String> arguments() {
        List<String> arguments = new ArrayList<>();
        // The overlay is this application's own; VLC's filename toast on top
        // of it would say everything twice.
        arguments.add("--no-video-title-show");
        String extra = System.getProperty(EXTRA_ARGS_PROPERTY, "").trim();
        if (!extra.isEmpty()) {
            LOG.log(Level.INFO, () -> "Extra libVLC arguments: " + extra);
            arguments.addAll(List.of(extra.split("\\s+")));
        }
        return arguments;
    }

    public LibVlc lib() {
        return lib;
    }

    public MemorySegment instance() {
        return instance;
    }
}
