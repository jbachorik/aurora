package mediacenter.ui;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import mediacenter.playback.Timecodes;
import mediacenter.playback.vlc.EmbeddedVlcPlayer;
import mediacenter.playback.vlc.VlcEngine;
import mediacenter.ui.components.ActivationGate;
import mediacenter.ui.components.Motion;

/**
 * The built-in player: libVLC decoding into this page, full screen.
 *
 * <p>Video lands in a {@link PixelBuffer} whose memory is the very buffer the
 * player writes — one copy per frame from decoder to screen, none added here.
 * The queue of episodes is this page's own: when one file ends the next
 * starts, and leaving the page (Esc or Backspace, like every page, or Ctrl+Q,
 * like VLC and the kiosk browser) stops everything, which is what stopping
 * means.
 *
 * <p>Keys while playing: Space or Enter pauses, Left/Right jump ten seconds
 * back / thirty forward, Up/Down a minute either way, N skips to the next
 * episode. Every touch brings up the overlay — title and clock — which slips
 * away on its own while playback runs and stays put while it is paused.
 */
public final class PlayerView implements View {

    /** One entry of the run this page plays: the file, and the words for it. */
    public record Entry(Path path, String title) {
    }

    private static final Duration OVERLAY_LINGER = Duration.seconds(3);
    private static final Duration CLOCK_TICK = Duration.millis(400);

    /**
     * How long the key hint stays when playback begins. Long enough to be read
     * twice from a sofa; any key dismisses it early, because a key pressed is
     * the hint understood.
     */
    private static final Duration KEYS_HINT_LINGER = Duration.seconds(8);

    /** The card the viewer gets a few seconds to read; also the documentation. */
    private static final List<String> KEY_HINTS = List.of(
            "Space  pause / resume",
            "← →  back 10 s / ahead 30 s",
            "↑ ↓  a minute either way",
            "N  P  next / previous episode",
            "Esc / Ctrl+Q  stop and go back");

    private static final long SMALL_BACK_MILLIS = -10_000;
    private static final long SMALL_FORWARD_MILLIS = 30_000;
    private static final long BIG_JUMP_MILLIS = 60_000;

    private final UiContext context;
    private final List<Entry> entries;
    /** What to do when a file verifiably starts playing — the history's way in. */
    private final BiConsumer<Path, String> onPlayed;

    private final StackPane root = new StackPane();
    private final ImageView videoView = new ImageView();
    private final Label titleLabel = new Label();
    private final Label clockLabel = new Label();
    private final VBox overlay = new VBox(2, titleLabel, clockLabel);
    private final PauseTransition overlayLinger = new PauseTransition(OVERLAY_LINGER);
    private final VBox keysHint = new VBox(3);
    private final PauseTransition keysHintLinger = new PauseTransition(KEYS_HINT_LINGER);
    private boolean keysHintDismissed;
    private final Timeline clock = new Timeline();
    private final ActivationGate activationGate = new ActivationGate();

    private final EmbeddedVlcPlayer player;

    private int position;
    private boolean started;
    /** Whether the entry at {@link #position} has proven itself with a frame. */
    private boolean currentRecorded;
    private volatile boolean disposed;

    private PixelBuffer<ByteBuffer> pixelBuffer;
    /** Collapses a burst of decoded frames into one scene-graph update. */
    private final AtomicBoolean frameQueued = new AtomicBoolean();

    public PlayerView(UiContext context, VlcEngine engine, List<Entry> entries,
                      BiConsumer<Path, String> onPlayed) {
        this.context = context;
        this.entries = List.copyOf(entries);
        this.onPlayed = onPlayed;

        root.getStyleClass().add("player-view");
        // See PhotoView: without this a first frame arriving before layout
        // would size the pane past the window, for good.
        root.setMinSize(0, 0);
        // Without this the keys never arrive: an event filter only sees events
        // aimed at this node or below it.
        root.setFocusTraversable(true);

        videoView.setPreserveRatio(true);
        videoView.setSmooth(true);
        videoView.fitWidthProperty().bind(root.widthProperty());
        videoView.fitHeightProperty().bind(root.heightProperty());

        titleLabel.getStyleClass().add("player-osd-title");
        clockLabel.getStyleClass().add("player-osd-time");
        overlay.getStyleClass().add("player-osd");
        overlay.setMaxWidth(Region.USE_PREF_SIZE);
        overlay.setMaxHeight(Region.USE_PREF_SIZE);
        overlay.setVisible(false);
        StackPane.setAlignment(overlay, Pos.BOTTOM_LEFT);

        keysHint.getStyleClass().add("player-osd");
        for (String hint : KEY_HINTS) {
            Label line = new Label(hint);
            line.getStyleClass().add("player-hint-line");
            keysHint.getChildren().add(line);
        }
        keysHint.setMaxWidth(Region.USE_PREF_SIZE);
        keysHint.setMaxHeight(Region.USE_PREF_SIZE);
        keysHint.setVisible(false);
        // The opposite corner from the title overlay, so the start of an
        // episode can show both without either covering the other.
        StackPane.setAlignment(keysHint, Pos.TOP_RIGHT);
        StackPane.setMargin(keysHint, new Insets(24));
        keysHintLinger.setOnFinished(event -> dismissKeysHint());

        root.getChildren().addAll(videoView, overlay, keysHint);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        root.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                activationGate.released();
            }
        });
        // The Enter that opened this page is still down; primed as though that
        // press had been seen, so its auto-repeat cannot pause what it started.
        activationGate.pressed(System.nanoTime());

        // Assigned before the handlers below mention it: a blank final may not
        // be read — even from inside a lambda — until it definitely has a value.
        player = new EmbeddedVlcPlayer(engine, new PlayerListener());

        overlayLinger.setOnFinished(event -> {
            if (player.isPlaying()) {
                overlay.setVisible(false);
            } else {
                // Paused: the overlay is the only sign the picture is not
                // simply stuck, so it stays as long as the pause does.
                overlayLinger.playFromStart();
            }
        });

        clock.getKeyFrames().add(new KeyFrame(CLOCK_TICK, event -> updateClock()));
        clock.setCycleCount(Timeline.INDEFINITE);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return entries.get(position).title();
    }

    @Override
    public boolean fullBleed() {
        return true;
    }

    @Override
    public void focusSelection() {
        root.requestFocus();
    }

    @Override
    public void onShown() {
        focusSelection();
        if (!started) {
            started = true;
            clock.play();
            startCurrent();
            showKeysHint();
        }
    }

    @Override
    public void onHidden() {
        // Nothing else stops any of this: the shell only pops the stack.
        disposed = true;
        clock.stop();
        overlayLinger.stop();
        keysHintLinger.stop();
        // The frame memory dies with the player, so the scene must let go of
        // it first — an ImageView still holding the buffer would be reading
        // freed pages.
        videoView.setImage(null);
        pixelBuffer = null;
        player.close();
    }

    // -- the run ------------------------------------------------------------

    private void startCurrent() {
        Entry entry = entries.get(position);
        currentRecorded = false;
        titleLabel.setText(entry.title());
        clockLabel.setText("");
        showOverlay();
        player.play(entry.path());
    }

    /** Moves along the run; off either end means the page is done and leaves. */
    private void step(int delta) {
        int next = position + delta;
        if (next < 0 || next >= entries.size()) {
            context.navigation().goBack();
            return;
        }
        position = next;
        startCurrent();
    }

    // -- input --------------------------------------------------------------

    private void handleKey(KeyEvent event) {
        dispatchKey(event);
        if (event.isConsumed()) {
            // A key pressed is the hint understood.
            dismissKeysHint();
        }
    }

    private void dispatchKey(KeyEvent event) {
        if (event.getCode() == KeyCode.Q && event.isControlDown()) {
            // Ctrl+Q closes VLC's own window and, through the bundled
            // extension, the kiosk browser. Playing inside this page instead
            // of in VLC must not cost the viewer that key: leaving whatever
            // has taken the screen stays one gesture, whichever thing it is.
            context.navigation().goBack();
            event.consume();
            return;
        }
        switch (event.getCode()) {
            case SPACE, ENTER -> {
                if (activationGate.pressed(System.nanoTime())) {
                    player.togglePause();
                }
                showOverlay();
                event.consume();
            }
            case LEFT -> seek(SMALL_BACK_MILLIS, event);
            case RIGHT -> seek(SMALL_FORWARD_MILLIS, event);
            case DOWN -> seek(-BIG_JUMP_MILLIS, event);
            case UP -> seek(BIG_JUMP_MILLIS, event);
            case N -> {
                step(1);
                event.consume();
            }
            case P -> {
                step(-1);
                event.consume();
            }
            default -> { }
        }
    }

    private void seek(long deltaMillis, KeyEvent event) {
        player.seekBy(deltaMillis);
        updateClock();
        showOverlay();
        event.consume();
    }

    // -- overlay ------------------------------------------------------------

    private void showKeysHint() {
        keysHint.setVisible(true);
        keysHintLinger.playFromStart();
    }

    private void dismissKeysHint() {
        if (keysHintDismissed || !keysHint.isVisible()) {
            return;
        }
        keysHintDismissed = true;
        keysHintLinger.stop();
        FadeTransition fade = new FadeTransition(Motion.GENTLE, keysHint);
        fade.setFromValue(keysHint.getOpacity());
        fade.setToValue(0);
        fade.setInterpolator(Motion.EASE);
        fade.setOnFinished(event -> keysHint.setVisible(false));
        fade.play();
    }

    private void showOverlay() {
        updateClock();
        overlay.setVisible(true);
        overlayLinger.playFromStart();
    }

    private void updateClock() {
        if (!overlay.isVisible() && clockLabel.getText() != null && !clockLabel.getText().isEmpty()) {
            return;
        }
        long length = player.lengthMillis();
        if (length <= 0) {
            return;
        }
        String reading = Timecodes.position(player.timeMillis(), length);
        clockLabel.setText(player.isPlaying() ? reading : reading + "   ⏸");
    }

    // -- the player's voice (libVLC threads; everything hops to the FX thread) --

    private final class PlayerListener implements EmbeddedVlcPlayer.Listener {

        @Override
        public void videoFormatChanged(int width, int height, ByteBuffer frame) {
            FxTasks.onFx(() -> {
                if (disposed) {
                    return;
                }
                pixelBuffer = new PixelBuffer<>(width, height, frame,
                        PixelFormat.getByteBgraPreInstance());
                videoView.setImage(new WritableImage(pixelBuffer));
            });
        }

        @Override
        public void frameRendered() {
            // One update per pulse, however fast the decoder runs: a queued
            // update covers every frame that lands before it executes.
            if (!frameQueued.compareAndSet(false, true)) {
                return;
            }
            FxTasks.onFx(() -> {
                frameQueued.set(false);
                if (disposed || pixelBuffer == null) {
                    return;
                }
                pixelBuffer.updateBuffer(buffer -> null);
                if (!currentRecorded) {
                    // A frame on screen is the proof the external player never
                    // gave: this file really played.
                    currentRecorded = true;
                    Entry entry = entries.get(position);
                    onPlayed.accept(entry.path(), entry.title());
                }
            });
        }

        @Override
        public void mediaEnded() {
            FxTasks.onFx(() -> {
                if (!disposed) {
                    step(1);
                }
            });
        }

        @Override
        public void playbackFailed(String message) {
            FxTasks.onFx(() -> {
                if (disposed) {
                    return;
                }
                context.navigation().showError(message);
                // A broken episode must not end the evening: the run carries
                // on, and only a run with nothing left leaves the page.
                step(1);
            });
        }
    }
}
