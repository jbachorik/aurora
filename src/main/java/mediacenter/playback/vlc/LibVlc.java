package mediacenter.playback.vlc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The slice of the libVLC 3.x C API this application uses, bound with the
 * Java FFM API — {@code java.lang.foreign} in {@code java.base}, so the
 * application stays one ordinary module and jlink stays happy. No JNA, no
 * generated code: about twenty functions, written down by hand.
 *
 * <p>All the {@code MemorySegment}s passed around here are opaque libVLC
 * handles ({@code libvlc_instance_t*}, {@code libvlc_media_player_t*}, …);
 * nothing dereferences them on the Java side. libVLC's API is thread-safe,
 * so these wrappers may be called from any thread — the rules about
 * <em>which</em> thread should drive stop/start live with the caller.
 *
 * <p>Loading and calling native code are restricted operations: the runtime
 * needs {@code --enable-native-access=media.center}, which the launcher
 * configuration supplies — that grant is what the suppression below stands on.
 */
@SuppressWarnings("restricted")
public final class LibVlc {

    private static final Logger LOG = Logger.getLogger(LibVlc.class.getName());

    private static final Linker LINKER = Linker.nativeLinker();

    // -- upcall shapes, public because the player builds stubs against them --

    /** {@code void* lock(void *opaque, void **planes)} */
    public static final FunctionDescriptor VIDEO_LOCK = FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code void unlock(void *opaque, void *picture, void *const *planes)} */
    public static final FunctionDescriptor VIDEO_UNLOCK = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code void display(void *opaque, void *picture)} */
    public static final FunctionDescriptor VIDEO_DISPLAY = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /**
     * {@code unsigned setup(void **opaque, char *chroma, unsigned *width,
     * unsigned *height, unsigned *pitches, unsigned *lines)}
     */
    public static final FunctionDescriptor VIDEO_FORMAT_SETUP = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    /** {@code void cleanup(void *opaque)} */
    public static final FunctionDescriptor VIDEO_FORMAT_CLEANUP =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);

    /** {@code void callback(const libvlc_event_t *event, void *userData)} */
    public static final FunctionDescriptor EVENT_CALLBACK = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    // -- the event types this application listens for (libvlc_events.h) ------

    public static final int EVENT_MEDIA_PLAYER_END_REACHED = 265;
    public static final int EVENT_MEDIA_PLAYER_ENCOUNTERED_ERROR = 266;

    private final MethodHandle libvlcNew;
    private final MethodHandle libvlcRelease;
    private final MethodHandle errmsg;
    private final MethodHandle mediaNewPath;
    private final MethodHandle mediaRelease;
    private final MethodHandle playerNew;
    private final MethodHandle playerRelease;
    private final MethodHandle playerSetMedia;
    private final MethodHandle playerPlay;
    private final MethodHandle playerPause;
    private final MethodHandle playerStop;
    private final MethodHandle playerIsPlaying;
    private final MethodHandle playerGetTime;
    private final MethodHandle playerSetTime;
    private final MethodHandle playerGetLength;
    private final MethodHandle playerEventManager;
    private final MethodHandle eventAttach;
    private final MethodHandle videoSetCallbacks;
    private final MethodHandle videoSetFormatCallbacks;

    private LibVlc(SymbolLookup lookup) {
        libvlcNew = downcall(lookup, "libvlc_new",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        libvlcRelease = downcall(lookup, "libvlc_release",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        errmsg = downcall(lookup, "libvlc_errmsg",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        mediaNewPath = downcall(lookup, "libvlc_media_new_path",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        mediaRelease = downcall(lookup, "libvlc_media_release",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        playerNew = downcall(lookup, "libvlc_media_player_new",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        playerRelease = downcall(lookup, "libvlc_media_player_release",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        playerSetMedia = downcall(lookup, "libvlc_media_player_set_media",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        playerPlay = downcall(lookup, "libvlc_media_player_play",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        playerPause = downcall(lookup, "libvlc_media_player_pause",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        playerStop = downcall(lookup, "libvlc_media_player_stop",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        playerIsPlaying = downcall(lookup, "libvlc_media_player_is_playing",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        playerGetTime = downcall(lookup, "libvlc_media_player_get_time",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        playerSetTime = downcall(lookup, "libvlc_media_player_set_time",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        playerGetLength = downcall(lookup, "libvlc_media_player_get_length",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        playerEventManager = downcall(lookup, "libvlc_media_player_event_manager",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        eventAttach = downcall(lookup, "libvlc_event_attach",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        videoSetCallbacks = downcall(lookup, "libvlc_video_set_callbacks",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        videoSetFormatCallbacks = downcall(lookup, "libvlc_video_set_format_callbacks",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    /**
     * Tries each candidate in order and binds the first library that loads.
     *
     * @param arena owns the loaded library; must stay open as long as any
     *              call through the returned binding can happen
     */
    public static Optional<LibVlc> load(List<LibVlcLocations.Candidate> candidates, Arena arena) {
        for (LibVlcLocations.Candidate candidate : candidates) {
            try {
                // Windows and macOS need the core loaded first; see
                // LibVlcLocations. A candidate whose core is missing simply
                // fails here and the next one is tried.
                if (candidate.core().isPresent()) {
                    SymbolLookup.libraryLookup(candidate.core().get(), arena);
                }
                SymbolLookup lookup = looksLikePath(candidate.library())
                        ? SymbolLookup.libraryLookup(Path.of(candidate.library()), arena)
                        : SymbolLookup.libraryLookup(candidate.library(), arena);
                LibVlc bound = new LibVlc(lookup);
                LOG.log(Level.INFO, () -> "Loaded libVLC from " + candidate.library());
                return Optional.of(bound);
            } catch (Throwable failure) {
                LOG.log(Level.FINE, "libVLC candidate did not load: " + candidate.library(), failure);
            }
        }
        return Optional.empty();
    }

    private static boolean looksLikePath(String library) {
        return library.indexOf('/') >= 0 || library.indexOf('\\') >= 0;
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        MemorySegment symbol = lookup.find(name)
                .orElseThrow(() -> new IllegalStateException("libVLC does not export " + name));
        return LINKER.downcallHandle(symbol, descriptor);
    }

    /** Builds a C function pointer for one of the descriptors above. */
    public static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor, Arena arena) {
        return LINKER.upcallStub(target, descriptor, arena);
    }

    // -- calls ---------------------------------------------------------------

    /** {@code libvlc_new}; NULL when the library refuses to start. */
    public MemorySegment newInstance(List<String> arguments, Arena arena) {
        MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, Math.max(1, arguments.size()));
        for (int i = 0; i < arguments.size(); i++) {
            argv.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(arguments.get(i)));
        }
        return (MemorySegment) invoke(libvlcNew, arguments.size(), argv);
    }

    public void releaseInstance(MemorySegment instance) {
        invoke(libvlcRelease, instance);
    }

    /** libVLC's own words for the calling thread's last failure, if it left any. */
    public Optional<String> lastError() {
        MemorySegment message = (MemorySegment) invoke(errmsg);
        if (message == null || message.equals(MemorySegment.NULL)) {
            return Optional.empty();
        }
        return Optional.of(message.reinterpret(Long.MAX_VALUE).getString(0));
    }

    public MemorySegment mediaNewPath(MemorySegment instance, Path file, Arena arena) {
        return (MemorySegment) invoke(mediaNewPath, instance, arena.allocateFrom(file.toString()));
    }

    public void mediaRelease(MemorySegment media) {
        invoke(mediaRelease, media);
    }

    public MemorySegment playerNew(MemorySegment instance) {
        return (MemorySegment) invoke(playerNew, instance);
    }

    public void playerRelease(MemorySegment player) {
        invoke(playerRelease, player);
    }

    public void playerSetMedia(MemorySegment player, MemorySegment media) {
        invoke(playerSetMedia, player, media);
    }

    public boolean playerPlay(MemorySegment player) {
        return (int) invoke(playerPlay, player) == 0;
    }

    /** Toggles pause; a player that has not started ignores it. */
    public void playerPause(MemorySegment player) {
        invoke(playerPause, player);
    }

    /** Blocks until playback has stopped — never call it from a libVLC callback. */
    public void playerStop(MemorySegment player) {
        invoke(playerStop, player);
    }

    public boolean playerIsPlaying(MemorySegment player) {
        return (int) invoke(playerIsPlaying, player) != 0;
    }

    public long playerTimeMillis(MemorySegment player) {
        return (long) invoke(playerGetTime, player);
    }

    public void playerSetTimeMillis(MemorySegment player, long timeMillis) {
        invoke(playerSetTime, player, timeMillis);
    }

    public long playerLengthMillis(MemorySegment player) {
        return (long) invoke(playerGetLength, player);
    }

    public MemorySegment playerEventManager(MemorySegment player) {
        return (MemorySegment) invoke(playerEventManager, player);
    }

    public void eventAttach(MemorySegment eventManager, int eventType, MemorySegment callback) {
        invoke(eventAttach, eventManager, eventType, callback, MemorySegment.NULL);
    }

    public void videoSetCallbacks(
            MemorySegment player, MemorySegment lock, MemorySegment unlock, MemorySegment display) {
        invoke(videoSetCallbacks, player, lock, unlock, display, MemorySegment.NULL);
    }

    public void videoSetFormatCallbacks(MemorySegment player, MemorySegment setup, MemorySegment cleanup) {
        invoke(videoSetFormatCallbacks, player, setup, cleanup);
    }

    /**
     * One funnel for every downcall: an invocation failing is a programming
     * error in this binding, not a condition callers are meant to handle.
     */
    private static Object invoke(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw new IllegalStateException("libVLC call failed", failure);
        }
    }
}
