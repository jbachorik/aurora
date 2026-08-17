package mediacenter.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import mediacenter.media.ExifOrientation;
import mediacenter.media.MediaAccessException;
import mediacenter.media.PhotoWalker;
import mediacenter.ui.components.ActivationGate;
import mediacenter.ui.components.Motion;
import mediacenter.ui.components.PhotoCache;

/**
 * Photographs, full screen.
 *
 * <p>Two viewings share this class: a slideshow of everything beneath a folder,
 * advancing on its own and looping; and one photograph with the arrows moving
 * through its own folder. The difference is entirely in what is collected and in
 * {@link PhotoRun}'s wrapping rule.
 */
final class PhotoView implements View {

    private static final Logger LOG = Logger.getLogger(PhotoView.class.getName());

    /**
     * A ceiling on one viewing. Large enough for any real shelf of holidays,
     * small enough that a wrong turn into a system directory cannot collect for
     * ever. Reaching it leaves the run incomplete, so the counter keeps its "+".
     */
    private static final int PHOTO_LIMIT = 5_000;

    /** Where a photograph goes before the window has been laid out. */
    private static final double FALLBACK_SIZE = 1920;

    /**
     * How quiet the activation key must go before a press counts as a new one.
     *
     * <p>Wider than {@link ActivationGate#DEFAULT_QUIET_GAP_NANOS} because the gap
     * being measured here is a different one. The gate is primed at construction
     * against the auto-repeat of the Enter that opened this page, so the interval it
     * has to cover is one full initial key-repeat delay — around 500ms on macOS,
     * Windows and GNOME, 660ms on X — and not the tens of milliseconds between
     * repeats inside a held sequence, which is what the default is sized for. Against
     * the default the first repeat lands outside the window, passes the gate, and
     * pauses the show on photograph one.
     *
     * <p>Costs nothing in normal use: an observed release re-arms the gate without
     * consulting this at all, so a deliberate second press always works.
     */
    private static final long ACTIVATION_QUIET_GAP_NANOS = 700_000_000L;

    private final UiContext context;
    private final Path folder;
    private final boolean slideshow;
    private final Path startAt;

    private final StackPane root = new StackPane();
    private final ImageView imageView = new ImageView();
    private final Label overlay = new Label();
    private final PhotoRun run;
    private final PhotoCache<Image> cache =
            new PhotoCache<>(PhotoCache.imageLoader(), PhotoCache.imageDisposer());

    private final ActivationGate activationGate = new ActivationGate(ACTIVATION_QUIET_GAP_NANOS);
    /**
     * Orientation is read from disk, so it is remembered rather than re-read on every
     * repaint.
     *
     * <p>Append-only on purpose: never cleared, never capped. It is what makes
     * {@link #display()} take its synchronous branch for the photograph already on
     * screen, and that is what puts the new image into the {@link ImageView} before
     * the one it replaced reports the error that {@link Image#cancel()} defers by a
     * pulse. The identity test in {@link #watchForFailure} rests on that ordering, so
     * clearing or capping this map would silently turn every cancelled decode back
     * into a counted failure. Five thousand paths and boxed integers is a trivial cost
     * beside a single decoded photograph.
     */
    private final Map<Path, Integer> orientations = new HashMap<>();
    /** The image a failure listener is attached to, and that listener; set and cleared together. */
    private Image watchedImage;
    private InvalidationListener watchedListener;
    private FadeTransition captionFade;
    /** A resize is acted on once it has stopped, not while it is happening. */
    private final PauseTransition resizeSettled = new PauseTransition(Duration.millis(250));

    private int index;
    private boolean showingSomething;
    private boolean seekDone;
    private boolean showingFailure;
    /**
     * Which photographs have failed since the last one that decoded — the set, not a
     * count of attempts. What the give-up branch asks is whether every photograph in
     * the run has been tried and failed, so it has to be told apart from one
     * photograph failing over and over: an arrow held at the end of a run that does
     * not wrap re-shows the same broken file once per auto-repeat, and counting
     * attempts would walk a short run into "these photographs could not be shown"
     * within a second of holding a key.
     */
    private final Set<Path> failedSinceLastSuccess = new HashSet<>();
    private double lastPaintedWidth;
    private double lastPaintedHeight;

    /** Set from the JavaFX thread, read by the timer thread. */
    private volatile boolean cancelled;
    private volatile boolean advancing;
    private final AtomicLong nextAdvanceAt = new AtomicLong(Long.MAX_VALUE);

    PhotoView(UiContext context, Path folder, boolean slideshow, Path startAt) {
        this.context = context;
        this.folder = folder;
        this.slideshow = slideshow;
        this.startAt = startAt;
        this.run = new PhotoRun(slideshow);

        root.getStyleClass().add("photo-view");
        root.setAlignment(Pos.CENTER);
        // The photograph is fitted to the page, so the page must never be sized by
        // the photograph. Without this a first painting that lands before the page
        // has been laid out — which is what happens whenever the walk finds
        // something before the first pulse — makes the pane grow to the size that
        // painting guessed, and it then stays larger than the window: the
        // photograph hangs off every edge and the caption sits below the bottom of
        // the screen, with nothing that ever brings either back.
        root.setMinSize(0, 0);
        // Without this the arrow keys never arrive: an event filter only sees
        // events aimed at this node or below it.
        root.setFocusTraversable(true);

        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        overlay.getStyleClass().add("photo-overlay");
        overlay.setVisible(false);
        StackPane.setAlignment(overlay, Pos.BOTTOM_LEFT);

        root.getChildren().addAll(imageView, overlay);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        root.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                activationGate.released();
            }
        });
        // The Enter that opened this page is still down, and its auto-repeat lands
        // here, on the page that has just taken the focus. A gate that starts armed
        // would let that first repeat through and pause the show on photograph one,
        // with nothing on screen to say why. Primed as though it had already seen
        // that press, so a press only counts once the key has been let go — the
        // release does arrive here, unlike the one that starts playback.
        activationGate.pressed(System.nanoTime());

        // The scene does not exist yet — this view is constructed before it is put
        // into the frame — so the first sizing waits for the pane to be laid out.
        // Debounced, not merely de-duplicated: width and height fire separately
        // and a drag changes the size on every pulse, so reacting to each one
        // would clear the cache and start three fresh decodes many times a second.
        resizeSettled.setOnFinished(event -> {
            if (cancelled) {
                return;
            }
            if (showingSomething && sizeChanged()) {
                cache.clear();
                display();
            }
        });
        InvalidationListener resize = observable -> resizeSettled.playFromStart();
        root.widthProperty().addListener(resize);
        root.heightProperty().addListener(resize);

        startWalk();
        if (slideshow) {
            startTimer();
        }
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
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
    public void onHidden() {
        // Nothing else stops these: the shell only pops the stack. The walk reads
        // this flag through the supplier it was given, so it stops between
        // directories rather than running on against a page nobody is looking at.
        cancelled = true;
        advancing = false;
        // The two asynchronous things here that no flag guards: a pending debounce
        // would settle after Esc and repaint a dead page, filling a cache that
        // will never be cleared again, and a caption fade would go on running
        // against a detached label.
        resizeSettled.stop();
        if (captionFade != null) {
            captionFade.stop();
        }
        // The cancelled flag already makes the listener harmless; letting go of it
        // here is so that the teardown inventory is the whole of it.
        stopWatching();
        cache.clear();
    }

    // -- collecting ---------------------------------------------------------

    private void startWalk() {
        context.backgroundExecutor().execute(() -> {
            try {
                PhotoWalker.Walk walk = PhotoWalker.collect(folder, slideshow, PHOTO_LIMIT,
                        () -> cancelled, batch -> Platform.runLater(() -> onBatch(batch)));
                Platform.runLater(() -> {
                    if (cancelled) {
                        return;
                    }
                    if (walk.truncated()) {
                        run.markTruncated();
                    }
                    run.markComplete();
                    if (run.isEmpty()) {
                        // A black screen with no explanation is the worst outcome
                        // here: nothing on it would say that Esc is the way out.
                        showFinalCaption("No photographs here.");
                    } else {
                        if (startAt != null && !seekDone) {
                            // The grid offered a photograph the walk never found.
                            // The two are meant to agree; if they ever do not, say
                            // so in the log rather than silently showing another.
                            LOG.warning("Not among the photographs collected: " + startAt);
                        }
                        refreshOverlay();
                    }
                });
            } catch (MediaAccessException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not collect photographs in " + folder, e);
                Platform.runLater(() -> {
                    if (cancelled) {
                        return;
                    }
                    run.markComplete();
                    if (!showingSomething) {
                        showFinalCaption("These photographs are not available.");
                    }
                });
            }
        });
    }

    /** On the JavaFX thread: the run is only ever mutated here. */
    private void onBatch(List<Path> batch) {
        if (cancelled) {
            return;
        }
        run.add(batch);
        if (!showingSomething) {
            // The photograph that was activated, if it has been collected yet.
            int wanted = startAt == null ? -1 : run.indexOf(startAt);
            seekDone = startAt == null || wanted >= 0;
            show(Math.max(wanted, 0));
        } else if (!seekDone) {
            // Seek once and once only. Re-seeking on every batch would drag the
            // viewer back to where they started after they had arrowed away.
            int wanted = run.indexOf(startAt);
            if (wanted >= 0) {
                seekDone = true;
                show(wanted);
            }
        } else {
            // Deliberately not refreshOverlay(): the caption belongs to a change
            // of photograph, and re-showing it for every directory the walk
            // finishes would leave it up for the whole walk.
            updateCounterIfShowing();
        }
    }

    /** Keeps the count honest as the walk grows, without re-showing a faded caption. */
    private void updateCounterIfShowing() {
        // Never over a failure message: that one is the only thing on a black
        // screen, and replacing it with a file name would leave no explanation.
        if (overlay.isVisible() && !showingFailure && !run.isEmpty()) {
            Path name = run.get(index).getFileName();
            overlay.setText((name == null ? run.get(index).toString() : name.toString())
                    + "        " + run.counterText(index));
        }
    }

    // -- showing ------------------------------------------------------------

    private void show(int target) {
        if (run.isEmpty()) {
            return;
        }
        index = Math.floorMod(target, run.size());
        showingSomething = true;
        showingFailure = false;
        resetTimer();
        display();
        // display() can fail synchronously — a revisited photograph whose image is
        // cached and already in error reports it from inside paint() — and the
        // caption it puts up must survive.
        if (!showingFailure) {
            refreshOverlay();
        }
    }

    /**
     * Reading the orientation opens the file, so it happens on the background
     * executor. The photograph already on screen stays until the answer arrives,
     * which reads better than showing one sideways and snapping it upright.
     */
    private void display() {
        Path photo = run.get(index);
        Integer known = orientations.get(photo);
        if (known != null) {
            paint(photo, known);
            return;
        }
        int shownIndex = index;
        FxTasks.run(
                context.backgroundExecutor(),
                () -> ExifOrientation.degreesFor(photo),
                degrees -> {
                    orientations.put(photo, degrees);
                    if (!cancelled && shownIndex == index) {
                        paint(photo, degrees);
                    }
                },
                failure -> {
                    orientations.put(photo, 0);
                    if (!cancelled && shownIndex == index) {
                        paint(photo, 0);
                    }
                });
    }

    /**
     * How much room there is in one direction: the page's own measurement, the
     * window's while the page has yet to be laid out, and only failing both the
     * fixed guess. The window is the better answer of the two available before a
     * layout, because it is the size the page is about to be given.
     */
    private static double fitInto(double pageSize, double windowSize) {
        if (pageSize > 0) {
            return pageSize;
        }
        return windowSize > 0 ? windowSize : FALLBACK_SIZE;
    }

    /** Whether the page has been given a different size since the last painting. */
    private boolean sizeChanged() {
        return Math.abs(root.getWidth() - lastPaintedWidth) >= 1
                || Math.abs(root.getHeight() - lastPaintedHeight) >= 1;
    }

    private void paint(Path photo, int degrees) {
        double width = fitInto(root.getWidth(), root.getScene() == null ? 0 : root.getScene().getWidth());
        double height = fitInto(root.getHeight(), root.getScene() == null ? 0 : root.getScene().getHeight());
        boolean quarterTurn = degrees == 90 || degrees == 270;

        // A rotation is applied after layout and does not re-lay-out, so a portrait
        // photograph has to be fitted into the box it will occupy once turned —
        // otherwise it is fitted landscape and then hangs off both sides.
        double fitWidth = quarterTurn ? height : width;
        double fitHeight = quarterTurn ? width : height;

        lastPaintedWidth = width;
        lastPaintedHeight = height;
        Image image = cache.show(run.photosView(), index, run.neighbours(index), fitWidth, fitHeight);
        imageView.setFitWidth(fitWidth);
        imageView.setFitHeight(fitHeight);
        imageView.setRotate(degrees);
        imageView.setImage(image);
        watchForFailure(image, photo);
    }

    /**
     * A background-loading image reports failure later, not when it is handed
     * over, so the answer has to be waited for rather than asked for.
     *
     * <p>The listener watches progress as well as error and removes itself from
     * both, exactly as {@code MediaTile.revealWhenLoaded} does: a listener
     * registered on one property is never invalidated by the other, so watching
     * only the error would leave the success path dead and the listener attached
     * to a cached image for as long as it lives.
     */
    private void watchForFailure(Image image, Path photo) {
        if (image == watchedImage) {
            // Already being watched, and still in flight. paint() is handed the
            // very same object more than once: an arrow at the end of a run that
            // does not wrap shows the photograph already on screen, and a resize
            // during the first orientation read paints it a second time. A
            // listener per repaint would count one failure once per listener, and
            // enough of those would trip the give-up branch on a run that is
            // mostly fine.
            return;
        }
        // At most one image is ever watched. Whatever was being watched is no
        // longer the one on screen, so its outcome no longer matters — and left
        // attached it would be a second listener the next time that image came
        // back round as a neighbour.
        stopWatching();
        if (image.isError()) {
            onDecodeFailed(photo);
            return;
        }
        if (image.getProgress() >= 1.0) {
            // Already decoded — a cache hit, or a repaint after a resize.
            // Registering again would stack a listener per repaint on one image.
            failedSinceLastSuccess.clear();
            return;
        }
        InvalidationListener[] listener = new InvalidationListener[1];
        listener[0] = observable -> {
            if (image.isError()) {
                stopWatching();
                // Both tests are needed. Image.cancel() reports itself as an
                // error one pulse later, so an image dropped by a resize must not
                // be read as a failure — hence the identity test. And display()
                // is asynchronous while an orientation is still being read, so
                // the image on screen can belong to the previous photograph —
                // hence the path test, without which a resize during that gap
                // skips the photograph that was never even tried.
                //
                // Both rest on {@code orientations} never being cleared: that is
                // what keeps display() synchronous for the photograph on screen,
                // so the swap to the new image happens in the same pulse as the
                // cancel and is in place before the error arrives.
                if (!cancelled && photo.equals(run.get(index)) && imageView.getImage() == image) {
                    onDecodeFailed(photo);
                }
            } else if (image.getProgress() >= 1.0) {
                stopWatching();
                failedSinceLastSuccess.clear();
            }
        };
        watchedImage = image;
        watchedListener = listener[0];
        image.progressProperty().addListener(listener[0]);
        image.errorProperty().addListener(listener[0]);
    }

    /** Lets go of the image being watched, so the two fields are only ever set together. */
    private void stopWatching() {
        if (watchedListener != null) {
            stopListening(watchedImage, watchedListener);
        }
        watchedImage = null;
        watchedListener = null;
    }

    private static void stopListening(Image image, InvalidationListener listener) {
        image.progressProperty().removeListener(listener);
        image.errorProperty().removeListener(listener);
    }

    /**
     * A photograph that will not decode must not stop the show. The caption is
     * left up only when there is nowhere to advance to — the last of a run, or a
     * single photograph — because advancing immediately would replace it before
     * anyone could read it. Giving up after a full pass keeps a folder of broken
     * files from spinning for ever.
     */
    private void onDecodeFailed(Path photo) {
        LOG.log(Level.FINE, () -> "Could not decode " + photo);
        // Recorded rather than counted, so that one broken photograph shown again and
        // again — an arrow held where the run does not wrap — is still one failure.
        failedSinceLastSuccess.add(photo);
        // Only once the run has ended does a full pass of failures mean anything.
        // While the walk continues, size() is whatever has been collected so far,
        // and one bad photograph in the first batch would end the show.
        if (run.complete() && failedSinceLastSuccess.size() >= Math.max(run.size(), 1)) {
            LOG.warning("None of these photographs could be shown");
            // Stopped, not merely reported: leaving the timer running would take
            // the next interval, fail again, and arrive back here for ever, at one
            // full-screen decode a time. The arrows still work.
            advancing = false;
            imageView.setImage(null);
            showingFailure = true;
            showFinalCaption("These photographs could not be shown.");
            return;
        }
        int from = index;
        int onwards = run.next(from);
        if (onwards != from) {
            // Posted rather than called: a cached image that is already in error
            // reports it from inside paint(), and advancing straight away would
            // nest paint() inside paint() once per broken file.
            Platform.runLater(() -> {
                // Compared against the index that failed, not against where we are
                // going: an arrow pressed in this pulse has already moved the
                // viewer, and they must not be dragged onwards from it.
                if (!cancelled && index == from) {
                    show(onwards);
                }
            });
        } else {
            imageView.setImage(null);
            showingFailure = true;
            showFinalCaption("This photograph could not be shown.");
        }
    }

    private void refreshOverlay() {
        if (run.isEmpty()) {
            return;
        }
        Path photo = run.get(index);
        Path name = photo.getFileName();
        showCaption((name == null ? photo.toString() : name.toString())
                + "        " + run.counterText(index));
    }

    /**
     * One transition, restarted. A new one per caption would leave several running
     * on the same label during a walk, and an older one finishing would hide a
     * caption a newer one had just put up.
     */
    private void showCaption(String text) {
        putCaption(text);
        if (captionFade == null) {
            captionFade = Motion.fadeOutAfter(overlay);
        }
        captionFade.playFromStart();
    }

    /**
     * A caption that stays. The three things this page can end on — no
     * photographs, none available, none showable — are the only thing on a black
     * screen, and fading them away leaves nothing to say that Esc is the way out.
     */
    private void showFinalCaption(String text) {
        if (captionFade != null) {
            captionFade.stop();
        }
        putCaption(text);
    }

    private void putCaption(String text) {
        overlay.setText(text);
        overlay.setVisible(true);
        overlay.setOpacity(1);
    }

    // -- input --------------------------------------------------------------

    private void handleKey(KeyEvent event) {
        switch (event.getCode()) {
            case LEFT -> {
                show(run.previous(index));
                event.consume();
            }
            case RIGHT -> {
                show(run.next(index));
                event.consume();
            }
            case ENTER, SPACE -> {
                // A thumb resting on the remote opened this page; without the gate
                // the auto-repeat would toggle the show off before it had begun.
                if (slideshow && activationGate.pressed(System.nanoTime())) {
                    advancing = !advancing;
                    resetTimer();
                }
                event.consume();
            }
            default -> { }
        }
    }

    // -- advancing ----------------------------------------------------------

    /**
     * Timed on an ordinary daemon thread rather than an animation: an animation
     * stops with the rendering pulse, and a display that goes to sleep would stop
     * the slideshow with it. The thread owns nothing but the deadline; every
     * change to what is on screen happens on the JavaFX thread.
     */
    private void startTimer() {
        advancing = true;
        resetTimer();
        Thread timer = new Thread(() -> {
            while (!cancelled) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (advancing && System.currentTimeMillis() >= nextAdvanceAt.get()) {
                    Platform.runLater(this::advance);
                }
            }
        }, "photo-slideshow");
        timer.setDaemon(true);
        timer.start();
    }

    private void advance() {
        if (cancelled) {
            return;
        }
        if (run.isEmpty()) {
            // Nothing to advance to yet; wait out another interval rather than
            // being asked again on the next tick.
            resetTimer();
            return;
        }
        // Checked again here: an arrow pressed between the timer reading the
        // deadline and this running would otherwise be skipped straight past.
        if (System.currentTimeMillis() < nextAdvanceAt.get()) {
            return;
        }
        int onwards = run.next(index);
        if (onwards != index) {
            show(onwards);
        } else {
            // Nothing to advance to yet — wait out another interval rather than
            // spinning on the deadline.
            resetTimer();
        }
    }

    /** A manual move buys a full interval on the picture arrived at. */
    private void resetTimer() {
        nextAdvanceAt.set(System.currentTimeMillis()
                + context.settings().get().slideshowSeconds() * 1000L);
    }
}
