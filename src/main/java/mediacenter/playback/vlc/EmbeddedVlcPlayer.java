package mediacenter.playback.vlc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One embedded playback session: a {@code libvlc_media_player_t} rendering
 * into a native BGRA buffer this class owns, one media at a time.
 *
 * <p>libVLC decodes and composes each frame — subtitles and all — and writes
 * it into the buffer through the vmem callbacks; the listener is told when a
 * frame is complete and hands the pixels to whatever is showing them. No
 * JavaFX types appear here, for the same reason {@code PlaybackService} has
 * none: the lifecycle can then be reasoned about, and driven, from anywhere.
 *
 * <p>Threading is the whole trick, so the rules are spelled out:
 *
 * <ul>
 *   <li>Listener calls arrive on libVLC's own threads, never this class's.
 *   <li>Media changes and stopping run on a private control thread, because
 *       {@code libvlc_media_player_stop} blocks and deadlocks if called from
 *       a libVLC callback. Callers may invoke {@link #play} from anywhere —
 *       including from {@link Listener#mediaEnded}, which is exactly how a
 *       queue advances.
 *   <li>The quick, thread-safe calls — pause, seek, clock reads — go straight
 *       through and may be made from any thread.
 * </ul>
 */
// Restricted FFM calls (reinterpret) are permitted by the launcher's
// --enable-native-access grant; see LibVlc.
@SuppressWarnings("restricted")
public final class EmbeddedVlcPlayer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EmbeddedVlcPlayer.class.getName());

    /** {@code RV32}: 32-bit BGRX little-endian, what JavaFX calls ByteBgraPre. */
    private static final byte[] CHROMA = {'R', 'V', '3', '2'};

    private static final int BYTES_PER_PIXEL = 4;

    /** Everything here is called on libVLC's threads. */
    public interface Listener {

        /**
         * A new video format is in effect; every following frame lands in this
         * buffer, {@code width * height} BGRA pixels, row after row.
         */
        void videoFormatChanged(int width, int height, ByteBuffer frame);

        /** The buffer holds one complete, composed frame. */
        void frameRendered();

        /** The current media ran off its end. {@link #play} may be called from here. */
        void mediaEnded();

        void playbackFailed(String message);
    }

    private final LibVlc lib;
    private final MemorySegment instance;
    private final MemorySegment player;
    private final Listener listener;

    /** Owns the upcall stubs and the frame memory; closed last, see {@link #close}. */
    private final Arena sessionArena = Arena.ofShared();

    private final ExecutorService control = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().name("vlc-control").daemon(true).unstarted(task));

    /** Written by the format callback, read by the lock callback and the listener. */
    private volatile MemorySegment frameBuffer = MemorySegment.NULL;
    private volatile boolean closed;

    public EmbeddedVlcPlayer(VlcEngine engine, Listener listener) {
        this.lib = engine.lib();
        this.instance = engine.instance();
        this.listener = listener;

        player = lib.playerNew(instance);
        if (player == null || player.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("libVLC could not create a media player");
        }

        lib.videoSetFormatCallbacks(player,
                upcall("setupFormat", LibVlc.VIDEO_FORMAT_SETUP,
                        MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                MemorySegment.class)),
                upcall("cleanupFormat", LibVlc.VIDEO_FORMAT_CLEANUP,
                        MethodType.methodType(void.class, MemorySegment.class)));
        lib.videoSetCallbacks(player,
                upcall("lockFrame", LibVlc.VIDEO_LOCK,
                        MethodType.methodType(MemorySegment.class, MemorySegment.class,
                                MemorySegment.class)),
                upcall("unlockFrame", LibVlc.VIDEO_UNLOCK,
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                                MemorySegment.class)),
                upcall("displayFrame", LibVlc.VIDEO_DISPLAY,
                        MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class)));

        MemorySegment events = lib.playerEventManager(player);
        MemorySegment onEvent = upcall("onEvent", LibVlc.EVENT_CALLBACK,
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
        lib.eventAttach(events, LibVlc.EVENT_MEDIA_PLAYER_END_REACHED, onEvent);
        lib.eventAttach(events, LibVlc.EVENT_MEDIA_PLAYER_ENCOUNTERED_ERROR, onEvent);
    }

    private MemorySegment upcall(String method, java.lang.foreign.FunctionDescriptor descriptor,
                                 MethodType type) {
        try {
            MethodHandle handle = MethodHandles.lookup()
                    .findVirtual(EmbeddedVlcPlayer.class, method, type)
                    .bindTo(this);
            return LibVlc.upcall(handle, descriptor, sessionArena);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Callback " + method + " cannot be bound", failure);
        }
    }

    // -- control -------------------------------------------------------------

    /** Starts (or switches to) a file. Safe from any thread, including callbacks. */
    public void play(Path mediaFile) {
        control.execute(() -> {
            if (closed) {
                return;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment media = lib.mediaNewPath(instance, mediaFile, arena);
                if (media == null || media.equals(MemorySegment.NULL)) {
                    listener.playbackFailed("This file could not be opened.");
                    return;
                }
                lib.playerSetMedia(player, media);
                // The player took its own reference; this one has done its job.
                lib.mediaRelease(media);
                if (!lib.playerPlay(player)) {
                    listener.playbackFailed(lib.lastError().orElse("Playback could not be started."));
                }
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Embedded playback failed to start", failure);
                listener.playbackFailed("Playback could not be started.");
            }
        });
    }

    public void togglePause() {
        if (!closed) {
            lib.playerPause(player);
        }
    }

    /** Jumps by a signed amount, clamped to the file; a player mid-open ignores it. */
    public void seekBy(long deltaMillis) {
        if (closed) {
            return;
        }
        long length = lib.playerLengthMillis(player);
        if (length <= 0) {
            return;
        }
        long now = lib.playerTimeMillis(player);
        // Not all the way to the end: landing exactly on it races the
        // EndReached event against the seek and can swallow the last second.
        long target = Math.clamp(now + deltaMillis, 0, Math.max(0, length - 1000));
        lib.playerSetTimeMillis(player, target);
    }

    /** Jumps to an absolute time, clamped to the file; a player mid-open ignores it. */
    public void seekTo(long millis) {
        if (closed) {
            return;
        }
        long length = lib.playerLengthMillis(player);
        if (length <= 0) {
            return;
        }
        // Same guard as seekBy: landing exactly on the end races EndReached.
        lib.playerSetTimeMillis(player, Math.clamp(millis, 0, Math.max(0, length - 1000)));
    }

    public long timeMillis() {
        return closed ? 0 : Math.max(0, lib.playerTimeMillis(player));
    }

    public long lengthMillis() {
        return closed ? 0 : Math.max(0, lib.playerLengthMillis(player));
    }

    public boolean isPlaying() {
        return !closed && lib.playerIsPlaying(player);
    }

    /**
     * Stops and releases everything, asynchronously — safe to call from a UI
     * thread. The caller must stop showing the frame buffer <em>before</em>
     * calling this: the memory is reclaimed once the player is gone.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        control.execute(() -> {
            try {
                lib.playerStop(player);
                lib.playerRelease(player);
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "Releasing the embedded player failed", failure);
            }
            // Only now is it certain no callback will run again, which is what
            // the stubs' lifetime — this arena — must outlast.
            sessionArena.close();
        });
        control.shutdown();
    }

    // -- libVLC callbacks (never let anything escape: it would take the JVM) --

    /** {@code format_setup}: answer with BGRA at the source's own size. */
    int setupFormat(MemorySegment opaqueRef, MemorySegment chroma,
                    MemorySegment width, MemorySegment height,
                    MemorySegment pitches, MemorySegment lines) {
        try {
            int w = width.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
            int h = height.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
            MemorySegment chromaBytes = chroma.reinterpret(CHROMA.length);
            for (int i = 0; i < CHROMA.length; i++) {
                chromaBytes.set(ValueLayout.JAVA_BYTE, i, CHROMA[i]);
            }
            int pitch = w * BYTES_PER_PIXEL;
            pitches.reinterpret(4).set(ValueLayout.JAVA_INT, 0, pitch);
            lines.reinterpret(4).set(ValueLayout.JAVA_INT, 0, h);

            long needed = (long) pitch * h;
            if (frameBuffer.byteSize() != needed) {
                // A run of episodes shares one size and so one buffer; only a
                // genuine format change allocates. The old memory stays valid
                // until close — a reader mid-frame must never lose its floor.
                frameBuffer = sessionArena.allocate(needed, 32);
            }
            listener.videoFormatChanged(w, h, frameBuffer.asByteBuffer());
            return 1;
        } catch (Throwable failure) {
            LOG.log(Level.SEVERE, "Video format setup failed", failure);
            return 0;
        }
    }

    void cleanupFormat(MemorySegment opaque) {
        // The buffer outlives the vout on purpose; nothing to do.
    }

    MemorySegment lockFrame(MemorySegment opaque, MemorySegment planes) {
        try {
            planes.reinterpret(ValueLayout.ADDRESS.byteSize())
                    .set(ValueLayout.ADDRESS, 0, frameBuffer);
        } catch (Throwable failure) {
            LOG.log(Level.SEVERE, "Video lock failed", failure);
        }
        return MemorySegment.NULL;
    }

    void unlockFrame(MemorySegment opaque, MemorySegment picture, MemorySegment planes) {
        // Single buffer, nothing to hand back.
    }

    void displayFrame(MemorySegment opaque, MemorySegment picture) {
        try {
            listener.frameRendered();
        } catch (Throwable failure) {
            LOG.log(Level.SEVERE, "Frame listener failed", failure);
        }
    }

    void onEvent(MemorySegment event, MemorySegment userData) {
        try {
            int type = event.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
            switch (type) {
                case LibVlc.EVENT_MEDIA_PLAYER_END_REACHED -> listener.mediaEnded();
                case LibVlc.EVENT_MEDIA_PLAYER_ENCOUNTERED_ERROR ->
                        listener.playbackFailed(lib.lastError().orElse("Playback failed."));
                default -> { }
            }
        } catch (Throwable failure) {
            LOG.log(Level.SEVERE, "Event listener failed", failure);
        }
    }
}
