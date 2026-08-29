package mediacenter.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
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
import mediacenter.config.Website;
import mediacenter.media.ArtworkResolver;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaScanner;
import mediacenter.playback.PlaybackResult;
import mediacenter.playback.PlaybackService;
import mediacenter.playback.cache.PlaybackPreparer;
import mediacenter.playback.vlc.VlcEngine;
import mediacenter.platform.KioskBrowser;
import mediacenter.platform.PlatformServices;
import mediacenter.platform.KioskExtension;
import mediacenter.remote.QrCode;
import mediacenter.remote.RemoteKiosk;
import mediacenter.history.PlaybackHistory;
import mediacenter.history.WatchedService;
import mediacenter.scrape.ScrapeService;
import mediacenter.scrape.VlcDurationProbe;
import mediacenter.ui.components.ArtworkCache;
import mediacenter.ui.components.Motion;

/**
 * The application frame: header, page stack, status banner, global keys and the
 * hide/play/restore lifecycle.
 */
public final class MediaCenterShell implements Navigation, RemoteKiosk {

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
    private final PlaybackPreparer playbackPreparer;
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

    /**
     * True while mirror copies are being looked up for the built-in player —
     * the moment between the play key and the player page appearing, when a
     * second press must not start a second run.
     */
    private volatile boolean embeddedPlayerPreparing;

    /**
     * The kiosk browser currently open, if any. Watched so a remote stop can
     * close it; whichever launch put a process here is the one that may clear
     * it again, which keeps a replaced browser's watcher from restoring the
     * window over its successor.
     */
    private final AtomicReference<Process> kioskProcess = new AtomicReference<>();

    /** What the kiosk browser is showing; null when it is closed. */
    private volatile String kioskUrl;

    /** The badge in the home screen's corner: a QR code to the remote control. */
    private final ImageView remoteQrView = new ImageView();
    private final Label remoteAddressLabel = new Label();
    private final VBox remoteBadge = new VBox(6, remoteQrView, remoteAddressLabel);
    private boolean remoteBadgeReady;

    public MediaCenterShell(
            Stage stage,
            AtomicReference<ApplicationSettings> settingsRef,
            SettingsStore settingsStore,
            PlaybackHistory history,
            WatchedService watched,
            PlaybackService playbackService,
            PlaybackPreparer playbackPreparer,
            PlatformServices platform,
            MediaScanner scanner,
            ArtworkResolver artworkResolver,
            ExecutorService backgroundExecutor) {

        this.stage = stage;
        this.settingsRef = settingsRef;
        this.settingsStore = settingsStore;
        this.platform = platform;
        this.playbackService = playbackService;
        this.playbackPreparer = playbackPreparer;
        this.backgroundExecutor = backgroundExecutor;

        this.context = new UiContext(
                this,
                this::settings,
                backgroundExecutor,
                scanner,
                artworkResolver,
                new ArtworkCache(),
                history,
                watched,
                platform,
                new ScrapeService(
                        () -> settings().scraper(), backgroundExecutor, Platform::runLater,
                        // The same libVLC install the player uses, borrowed to
                        // read film durations for the scraper's cross-check.
                        new VlcDurationProbe(() -> settings().vlcPath()),
                        // Watched marks and history follow a film the tidy-up
                        // moved into its own folder.
                        playbackService::recordFileMoved));

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
                + "Home  Home screen        F5  Refresh        W  Watched");
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

        remoteBadge.getStyleClass().add("remote-badge");
        remoteAddressLabel.getStyleClass().add("remote-badge-address");
        remoteBadge.setAlignment(Pos.CENTER);
        remoteBadge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // Purely informational: it must never take a click from the page under it.
        remoteBadge.setMouseTransparent(true);
        remoteBadge.setVisible(false);
        StackPane.setAlignment(remoteBadge, Pos.BOTTOM_RIGHT);
        // Clear of the hint bar along the bottom.
        StackPane.setMargin(remoteBadge, new Insets(0, 24, 56, 0));

        rootPane.getChildren().addAll(frame, bannerBox, remoteBadge);
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

        // The QR badge belongs to the main menu alone; any page pushed over it
        // takes it away.
        remoteBadge.setVisible(remoteBadgeReady && view == homeView);

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

    /**
     * Sleeping happens off the JavaFX thread: the helper is a process, and on a
     * machine that takes its time going down the wait would otherwise freeze the
     * screen that is still showing. Nothing is torn down first — the media center
     * is simply there again when the computer wakes.
     */
    @Override
    public void sleepComputer() {
        backgroundExecutor.execute(() -> {
            try {
                platform.sleepComputer();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not put the computer to sleep", e);
                FxTasks.onFx(() -> showError("This computer could not be put to sleep."));
            }
        });
    }

    /**
     * Unlike playback, the window stays up — full screen, behind the browser.
     *
     * <p>The browser is closed by the viewer, not by us, so we hear about it only
     * once it has already gone. Hiding the window would leave the desktop showing
     * from that moment until the window is back, with a fresh full-screen
     * transition on top of it; staying behind means there is nothing to come back
     * from and the media center is simply there again.
     */
    @Override
    public void openWebsite(Website website) {
        Optional<Path> browser = settings().browserPath();
        if (browser.isEmpty()) {
            // Without a program of our own there is no process to watch and no
            // way back; better one plain sentence than a tile that half-works.
            showError("Choose a browser in Settings first — website tiles open with it.");
            return;
        }
        LOG.log(Level.INFO, () -> "Opening website " + website.url());
        backgroundExecutor.execute(() -> {
            // A browser already open — a remote request replacing the page —
            // is closed first. Taking it out of the reference before the
            // destroy keeps its watcher from restoring the window (the
            // compare-and-set below fails for it), so the handover is silent.
            Process previous = kioskProcess.getAndSet(null);
            kioskUrl = null; // not stale even if the launch below fails
            if (previous != null) {
                previous.destroy();
            }
            try {
                Path dataDirectory = platform.applicationDataDirectory();
                List<String> command = KioskBrowser.commandFor(
                        browser.get(),
                        website.url(),
                        settings().browserScalePercent(),
                        dataDirectory.resolve("browser-profile"),
                        KioskExtension.ensureInstalled(dataDirectory));
                LOG.log(Level.INFO, () -> "Starting browser: " + String.join(" ", command));
                Process process = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                kioskProcess.set(process);
                kioskUrl = website.url();
                FxTasks.onFx(stage::toBack);
                int exitCode = process.waitFor();
                LOG.log(Level.INFO, () -> "Browser closed with exit code " + exitCode + ", returning");
                if (kioskProcess.compareAndSet(process, null)) {
                    kioskUrl = null;
                    FxTasks.onFx(this::restoreAfterPlayback);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                FxTasks.onFx(this::restoreAfterPlayback);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "The browser could not be started", e);
                FxTasks.onFx(() -> {
                    restoreAfterPlayback();
                    showError("The browser could not be started. Check its path in Settings.");
                });
            }
        });
    }

    // -- RemoteKiosk ---------------------------------------------------------
    //
    // Called from the remote-control server's worker threads. Everything here
    // stays off the JavaFX thread and hands UI work to it explicitly.

    @Override
    public Optional<String> openUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.of("The address is empty.");
        }
        if (settings().browserPath().isEmpty()) {
            return Optional.of("No browser is configured — choose one in Settings on the TV first.");
        }
        openWebsite(Website.create("Remote", url));
        return Optional.empty();
    }

    @Override
    public boolean stopBrowser() {
        Process process = kioskProcess.get();
        if (process == null) {
            return false;
        }
        // The watcher in openWebsite sees the process die and restores the
        // window; going home as well is what "stop" promises the phone.
        process.destroy();
        FxTasks.onFx(this::goHome);
        return true;
    }

    @Override
    public Optional<String> currentUrl() {
        return Optional.ofNullable(kioskUrl);
    }

    // -- remote-control badge ------------------------------------------------

    /**
     * Puts the remote control's address on the main menu, as a QR code for a
     * phone camera with the address written under it. Call on the JavaFX
     * thread once the server is up.
     */
    public void showRemoteControl(String address) {
        LOG.log(Level.INFO, () -> "Remote control reachable at " + address);
        remoteQrView.setImage(qrImage(QrCode.encode(address)));
        remoteAddressLabel.setText(address);
        remoteBadgeReady = true;
        remoteBadge.setVisible(currentView() == homeView);
    }

    /**
     * Renders the symbol at an integer scale straight into the image — no
     * resampling afterwards, which would blur the modules a camera needs
     * crisp. Always dark on white, whatever the theme: contrast is what makes
     * it scannable, and the white field doubles as the quiet zone.
     */
    private static WritableImage qrImage(QrCode qr) {
        final int quietModules = 4;
        final int scale = Math.max(3, 132 / qr.size());
        int sizePixels = (qr.size() + quietModules * 2) * scale;
        WritableImage image = new WritableImage(sizePixels, sizePixels);
        PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < sizePixels; y++) {
            for (int x = 0; x < sizePixels; x++) {
                int moduleX = x / scale - quietModules;
                int moduleY = y / scale - quietModules;
                boolean dark = moduleX >= 0 && moduleX < qr.size()
                        && moduleY >= 0 && moduleY < qr.size()
                        && qr.moduleAt(moduleX, moduleY);
                writer.setArgb(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return image;
    }

    @Override
    public void play(MediaItem item) {
        play(item, List.of());
    }

    @Override
    public void play(MediaItem item, List<MediaItem> playOnwards) {
        startPlayback(
                new PlayerView.Entry(item.path(), item.displayName()),
                playOnwards.stream()
                        .map(following -> new PlayerView.Entry(following.path(), following.displayName()))
                        .toList());
    }

    @Override
    public void play(Path mediaFile, String displayTitle) {
        startPlayback(new PlayerView.Entry(mediaFile, displayTitle), List.of());
    }

    private void startPlayback(PlayerView.Entry first, List<PlayerView.Entry> playOnwards) {
        if (playbackService.isPlaying() || embeddedPlayerPreparing) {
            return;
        }
        if (System.nanoTime() < acceptPlaybackFromNanos) {
            LOG.fine("Ignoring a playback request that arrived while the UI was coming back");
            return;
        }
        if (settings().embeddedPlayer() && startEmbeddedPlayback(first, playOnwards)) {
            return;
        }
        LOG.log(Level.INFO, () -> "Playback requested for " + first.path()
                + (playOnwards.isEmpty() ? "" : ", playing onwards through " + playOnwards.size() + " more"));
        // The window stays up while the file is prepared, so buffering progress
        // has somewhere to show; it hides when the player is really coming.
        playbackService.play(
                first.path(),
                playOnwards.stream().map(PlayerView.Entry::path).toList(),
                first.title(),
                this::showInfo,
                this::hideForPlayback,
                this::onPlaybackFinished);
    }

    /**
     * Plays inside the window when the built-in player is chosen and libVLC is
     * loadable — and otherwise says so once and lets the external window take
     * over, because a viewer with a film picked out wants the film, not a
     * settings lecture.
     */
    private boolean startEmbeddedPlayback(PlayerView.Entry first, List<PlayerView.Entry> playOnwards) {
        Optional<VlcEngine> engine = VlcEngine.load(settings().vlcPath());
        if (engine.isEmpty()) {
            showError("The built-in player could not load libVLC; using the VLC window instead.");
            return false;
        }
        List<PlayerView.Entry> entries = new ArrayList<>(playOnwards.size() + 1);
        entries.add(first);
        entries.addAll(playOnwards);
        LOG.log(Level.INFO, () -> "Embedded playback of " + first.path()
                + (playOnwards.isEmpty() ? "" : ", playing onwards through " + playOnwards.size() + " more"));
        if (playbackPreparer == null) {
            push(new PlayerView(context, engine.get(), entries, path -> path,
                    playbackService::recordPlayed));
            return true;
        }
        // Looking mirror copies up stats their sources, and a stat against a
        // dead share blocks — so it happens off the UI thread, and the page is
        // pushed when the answers are in. Entries keep the paths the viewer
        // chose; only what the player opens is swapped for a local copy.
        embeddedPlayerPreparing = true;
        List<Path> paths = entries.stream().map(PlayerView.Entry::path).toList();
        backgroundExecutor.execute(() -> {
            Map<Path, Path> copies = playbackPreparer.completedCopies(paths);
            FxTasks.onFx(() -> {
                embeddedPlayerPreparing = false;
                push(new PlayerView(context, engine.get(), entries,
                        path -> copies.getOrDefault(path, path),
                        playbackService::recordPlayed));
            });
        });
        return true;
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
