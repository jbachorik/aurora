package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import mediacenter.config.ApplicationSettings;
import mediacenter.config.SettingsStore;
import mediacenter.media.ArtworkResolver;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaScanner;
import mediacenter.playback.PlaybackResult;
import mediacenter.playback.PlaybackService;
import mediacenter.platform.PlatformServices;
import mediacenter.history.PlaybackHistory;
import mediacenter.ui.components.ArtworkCache;
import mediacenter.ui.components.Motion;

/**
 * The application frame: header, page stack, status banner, global keys and the
 * hide/play/restore lifecycle.
 */
public final class MediaCenterShell implements Navigation {

    private static final Logger LOG = Logger.getLogger(MediaCenterShell.class.getName());
    private static final Duration BANNER_DURATION = Duration.seconds(6);

    /**
     * Grace period after the window comes back from playback. Key presses that
     * were queued while the media center was hidden must not immediately start
     * the same file again.
     */
    private static final long PLAYBACK_GUARD_NANOS = 800_000_000L;

    /** How long the pointer may sit still before it is hidden. */
    private static final Duration CURSOR_IDLE_DELAY = Duration.seconds(3);

    private static final String HIDDEN_CURSOR_CLASS = "cursor-hidden";

    private final Stage stage;
    private final SettingsStore settingsStore;
    private final PlatformServices platform;
    private final PlaybackService playbackService;
    private final ExecutorService backgroundExecutor;

    private final StackPane rootPane = new StackPane();
    private final BorderPane frame = new BorderPane();
    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final Label bannerLabel = new Label();
    private final StackPane bannerBox = new StackPane(bannerLabel);
    private final PauseTransition bannerTimer = new PauseTransition(BANNER_DURATION);
    private final PauseTransition cursorIdleTimer = new PauseTransition(CURSOR_IDLE_DELAY);

    private final Deque<View> viewStack = new ArrayDeque<>();
    private final UiContext context;
    private final HomeView homeView;
    private final AtomicReference<ApplicationSettings> settingsRef;
    private long acceptPlaybackFromNanos;

    public MediaCenterShell(
            Stage stage,
            AtomicReference<ApplicationSettings> settingsRef,
            SettingsStore settingsStore,
            PlaybackHistory history,
            PlaybackService playbackService,
            PlatformServices platform,
            MediaScanner scanner,
            ArtworkResolver artworkResolver,
            ExecutorService backgroundExecutor) {

        this.stage = stage;
        this.settingsRef = settingsRef;
        this.settingsStore = settingsStore;
        this.platform = platform;
        this.playbackService = playbackService;
        this.backgroundExecutor = backgroundExecutor;

        this.context = new UiContext(
                this,
                this::settings,
                backgroundExecutor,
                scanner,
                artworkResolver,
                new ArtworkCache(),
                history,
                platform);

        buildFrame();
        this.homeView = new HomeView(context);
        viewStack.push(homeView);
        showCurrentView(Direction.NONE);
    }

    /** The node to put into the scene. */
    public Parent node() {
        return rootPane;
    }

    /** Installs the global input handling; call once the scene exists. */
    public void attachToScene() {
        Scene scene = rootPane.getScene();
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleGlobalKey);
        installCursorAutoHide(scene);
    }

    /**
     * Hides the pointer while it is not being used.
     *
     * <p>On a television an arrow parked in the middle of the poster wall is
     * just clutter; it comes straight back on the first movement.
     */
    private void installCursorAutoHide(Scene scene) {
        cursorIdleTimer.setOnFinished(event -> {
            if (!rootPane.getStyleClass().contains(HIDDEN_CURSOR_CLASS)) {
                rootPane.getStyleClass().add(HIDDEN_CURSOR_CLASS);
                LOG.fine("Pointer idle, hiding it");
            }
        });
        // Deliberately not MouseEvent.ANY: enter/exit events are synthesized as the
        // UI changes under a stationary pointer, which would keep it awake forever.
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> wakeCursor());
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> wakeCursor());
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> wakeCursor());
        cursorIdleTimer.playFromStart();
    }

    private void wakeCursor() {
        rootPane.getStyleClass().remove(HIDDEN_CURSOR_CLASS);
        cursorIdleTimer.playFromStart();
    }

    /** Focus the current page, e.g. after the window is shown. */
    public void focusCurrentView() {
        currentView().focusSelection();
    }

    // -- frame --------------------------------------------------------------

    private void buildFrame() {
        rootPane.getStyleClass().add("root-pane");

        titleLabel.getStyleClass().add("header-title");
        subtitleLabel.getStyleClass().add("header-subtitle");

        VBox headerText = new VBox(2, titleLabel, subtitleLabel);
        headerText.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(headerText, spacer);
        header.getStyleClass().add("header");
        header.setPadding(new Insets(20, 32, 12, 32));
        header.setAlignment(Pos.CENTER_LEFT);

        Label hints = new Label("Arrows  Move        Enter  Select        Esc / Backspace  Back        "
                + "Home  Home screen        F5  Refresh");
        hints.getStyleClass().add("hint-bar");
        HBox footer = new HBox(hints);
        footer.getStyleClass().add("footer");
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(10, 32, 14, 32));

        frame.setTop(header);
        frame.setBottom(footer);

        bannerLabel.getStyleClass().add("banner-label");
        bannerLabel.setWrapText(true);
        bannerBox.getStyleClass().add("banner");
        bannerBox.setVisible(false);
        bannerBox.setManaged(false);
        bannerBox.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(bannerBox, Pos.TOP_CENTER);
        StackPane.setMargin(bannerBox, new Insets(96, 32, 0, 32));
        bannerTimer.setOnFinished(event -> hideBanner());

        rootPane.getChildren().addAll(frame, bannerBox);
    }

    private View currentView() {
        return viewStack.peek();
    }

    /** Shows the current page, sliding it in from the direction travelled. */
    private void showCurrentView(Direction direction) {
        View view = currentView();
        frame.setCenter(view.node());

        // A full-bleed page takes the header and the hint bar with it, and gives
        // them back when it leaves.
        boolean chrome = !view.fullBleed();
        frame.getTop().setVisible(chrome);
        frame.getTop().setManaged(chrome);
        frame.getBottom().setVisible(chrome);
        frame.getBottom().setManaged(chrome);

        titleLabel.setText(view.title());
        String subtitle = view.subtitle();
        subtitleLabel.setText(subtitle);
        subtitleLabel.setVisible(!subtitle.isBlank());
        subtitleLabel.setManaged(!subtitle.isBlank());
        view.onShown();

        switch (direction) {
            case FORWARD -> Motion.slideFadeIn(view.node(), Motion.PAGE_SLIDE, Motion.NORMAL);
            case BACKWARD -> Motion.slideFadeIn(view.node(), -Motion.PAGE_SLIDE, Motion.NORMAL);
            case NONE -> Motion.fadeIn(view.node(), Motion.NORMAL);
        }
    }

    /** Which way the viewer is moving through the page stack. */
    private enum Direction { FORWARD, BACKWARD, NONE }

    private void push(View view) {
        viewStack.push(view);
        showCurrentView(Direction.FORWARD);
    }

    // -- global keys --------------------------------------------------------

    private void handleGlobalKey(KeyEvent event) {
        // Never steal keys from a text field in Settings.
        if (event.getTarget() instanceof TextInputControl) {
            return;
        }
        switch (event.getCode()) {
            case ESCAPE, BACK_SPACE -> {
                goBack();
                event.consume();
            }
            case HOME -> {
                goHome();
                event.consume();
            }
            case F5 -> {
                currentView().refresh();
                event.consume();
            }
            default -> { }
        }
    }

    // -- Navigation ---------------------------------------------------------

    @Override
    public void openRoots(String title, List<MediaRoot> roots) {
        if (roots.size() == 1) {
            MediaRoot root = roots.getFirst();
            browse(root, root.path());
        } else {
            push(new RootsView(this, title, roots));
        }
    }

    @Override
    public void browse(MediaRoot root, Path folder) {
        push(new BrowseView(context, root, folder));
    }

    @Override
    public void openSettings() {
        push(new SettingsView(context, platform, this::applySettings));
    }

    @Override
    public void openBrowserOrDesktop() {
        stepAsideForTheDesktop();
        backgroundExecutor.execute(() -> {
            try {
                platform.openBrowser(settings().browserPath());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not open the browser", e);
                FxTasks.onFx(() -> showError("The configured browser could not be started."));
            }
        });
    }

    @Override
    public void play(MediaItem item) {
        play(item, List.of());
    }

    @Override
    public void play(MediaItem item, List<MediaItem> playOnwards) {
        startPlayback(item.path(), playOnwards.stream().map(MediaItem::path).toList(),
                item.displayName());
    }

    @Override
    public void play(Path mediaFile, String displayTitle) {
        startPlayback(mediaFile, List.of(), displayTitle);
    }

    private void startPlayback(Path mediaFile, List<Path> playOnwards, String displayTitle) {
        if (playbackService.isPlaying()) {
            return;
        }
        if (System.nanoTime() < acceptPlaybackFromNanos) {
            LOG.fine("Ignoring a playback request that arrived while the UI was coming back");
            return;
        }
        LOG.log(Level.INFO, () -> "Playback requested for " + mediaFile
                + (playOnwards.isEmpty() ? "" : ", playing onwards through " + playOnwards.size() + " more"));
        hideForPlayback();
        playbackService.play(mediaFile, playOnwards, displayTitle, this::onPlaybackFinished);
    }

    @Override
    public void openSlideshow(Path folder) {
        push(new PhotoView(context, folder, true, null));
    }

    @Override
    public void openPhoto(Path folder, Path photo) {
        push(new PhotoView(context, folder, false, photo));
    }

    @Override
    public void goBack() {
        if (viewStack.size() <= 1) {
            return;
        }
        View leaving = viewStack.pop();
        leaving.onHidden();
        showCurrentView(Direction.BACKWARD);
    }

    @Override
    public void goHome() {
        boolean moved = viewStack.size() > 1;
        while (viewStack.size() > 1) {
            viewStack.pop().onHidden();
        }
        showCurrentView(moved ? Direction.BACKWARD : Direction.NONE);
    }

    @Override
    public void exitApplication() {
        LOG.info("Exiting on user request");
        Platform.exit();
    }

    @Override
    public void showError(String message) {
        showBanner(message, true);
    }

    @Override
    public void showInfo(String message) {
        showBanner(message, false);
    }

    // -- playback lifecycle -------------------------------------------------

    /**
     * Gets out of the way so the desktop is visible, and remembers to come back
     * full screen.
     *
     * <p>Minimising is not enough on its own: a full-screen window on macOS owns
     * a space of its own, and minimising it makes the window manager animate the
     * way out of that space first — a second or so of the media center sliding
     * about before any of the desktop appears. Dropping out of full screen first
     * is the same order {@link #hideForPlayback()} uses, and for the same reason.
     */
    private void stepAsideForTheDesktop() {
        boolean wasFullScreen = stage.isFullScreen();
        if (wasFullScreen) {
            stage.setFullScreen(false);
        }
        stage.setIconified(true);
        if (wasFullScreen) {
            restoreFullScreenWhenBack();
        }
    }

    /** Puts full screen back the first time the window is restored, then stops listening. */
    private void restoreFullScreenWhenBack() {
        stage.iconifiedProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> value,
                                Boolean was,
                                Boolean iconified) {
                if (!iconified) {
                    stage.iconifiedProperty().removeListener(this);
                    stage.setFullScreen(settings().fullScreen());
                }
            }
        });
    }

    private void hideForPlayback() {
        // Leaving full screen first keeps the window manager from putting the
        // media center back on top of the player.
        if (stage.isFullScreen()) {
            stage.setFullScreen(false);
        }
        stage.hide();
    }

    private void onPlaybackFinished(PlaybackResult result) {
        restoreAfterPlayback();
        switch (result) {
            case PlaybackResult.Failed failure -> showError(failure.userMessage());
            case PlaybackResult.Completed completed ->
                    LOG.log(Level.FINE, () -> "Player exited with code " + completed.exitCode());
        }
    }

    private void restoreAfterPlayback() {
        acceptPlaybackFromNanos = System.nanoTime() + PLAYBACK_GUARD_NANOS;
        stage.show();
        stage.setFullScreen(settings().fullScreen());
        stage.toFront();
        stage.requestFocus();
        homeView.refresh();
        currentView().onShown();
    }

    // -- settings -----------------------------------------------------------

    /** Applies a settings change: kept in memory immediately, written in the background. */
    public void applySettings(ApplicationSettings updated) {
        boolean themeChanged = settings().theme() != updated.theme();
        settingsRef.set(updated);
        stage.setFullScreen(updated.fullScreen());
        if (themeChanged) {
            applyTheme(updated);
        }
        backgroundExecutor.execute(() -> {
            if (!settingsStore.save(updated)) {
                FxTasks.onFx(() -> showError("Settings could not be saved."));
            }
        });
    }

    public ApplicationSettings settings() {
        return settingsRef.get();
    }

    /**
     * Swaps the palette and rebuilds the pages behind it.
     *
     * <p>Tile artwork placeholders are coloured per theme when the tile is built,
     * so the pages already on the stack have to be rebuilt rather than merely
     * restyled.
     */
    private void applyTheme(ApplicationSettings updated) {
        LOG.log(Level.INFO, () -> "Switching to the " + updated.theme() + " theme");
        if (rootPane.getScene() != null) {
            Stylesheets.apply(rootPane.getScene(), updated.theme());
        }
        for (View view : viewStack) {
            view.refresh();
        }
    }

    // -- banner -------------------------------------------------------------

    private void showBanner(String message, boolean isError) {
        bannerLabel.setText(message);
        bannerBox.getStyleClass().remove("banner-error");
        if (isError) {
            bannerBox.getStyleClass().add("banner-error");
        }
        boolean alreadyShowing = bannerBox.isVisible();
        bannerBox.setVisible(true);
        bannerBox.setManaged(true);
        if (!alreadyShowing) {
            // Drops in from just above; replacing the text of a banner that is
            // already up would only make the message harder to read.
            bannerBox.setTranslateY(-16);
            TranslateTransition drop = new TranslateTransition(Motion.NORMAL, bannerBox);
            drop.setFromY(-16);
            drop.setToY(0);
            drop.setInterpolator(Motion.EASE);
            drop.play();
            Motion.fadeIn(bannerBox, Motion.NORMAL);
        }
        bannerTimer.playFromStart();
    }

    private void hideBanner() {
        FadeTransition dismiss = new FadeTransition(Motion.NORMAL, bannerBox);
        dismiss.setFromValue(bannerBox.getOpacity());
        dismiss.setToValue(0);
        dismiss.setInterpolator(Motion.EASE);
        dismiss.setOnFinished(event -> {
            bannerBox.setVisible(false);
            bannerBox.setManaged(false);
        });
        dismiss.play();
    }
}
