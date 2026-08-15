package mediacenter;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;

import mediacenter.config.ApplicationSettings;
import mediacenter.config.SettingsStore;
import mediacenter.history.HistoryStore;
import mediacenter.history.PlaybackHistory;
import mediacenter.media.ArtworkResolver;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaScanner;
import mediacenter.platform.PlatformServices;
import mediacenter.playback.PlaybackService;
import mediacenter.playback.PlayerLauncher;
import mediacenter.playback.VlcPlayerLauncher;
import mediacenter.ui.MediaCenterShell;

/**
 * JavaFX entry point.
 *
 * <p>Startup shows the window first and does everything that can block —
 * scanning, artwork, VLC discovery — afterwards on background threads, so an
 * offline NAS can never delay the home screen.
 */
public final class MediaCenterApp extends Application {

    private static final Logger LOG = Logger.getLogger(MediaCenterApp.class.getName());

    private static final String STYLESHEET = "/mediacenter/ui/mediacenter.css";
    private static final double INITIAL_WIDTH = 1600;
    private static final double INITIAL_HEIGHT = 900;

    private ExecutorService backgroundExecutor;

    /** Required by JavaFX, which instantiates this class reflectively. */
    public MediaCenterApp() {
    }

    @Override
    public void start(Stage stage) {
        // The window is hidden during playback; the JVM must survive that.
        Platform.setImplicitExit(false);

        PlatformServices platform = PlatformServices.detect();
        Path dataDirectory = platform.applicationDataDirectory();

        SettingsStore settingsStore = new SettingsStore(dataDirectory);
        HistoryStore historyStore = new HistoryStore(dataDirectory);

        ApplicationSettings settings = settingsStore.load();
        PlaybackHistory history = historyStore.load();
        AtomicReference<ApplicationSettings> settingsRef = new AtomicReference<>(settings);

        logStartup(platform, dataDirectory, settings);

        backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();

        PlayerLauncher playerLauncher = new VlcPlayerLauncher(() -> settingsRef.get().vlcPath());
        PlaybackService playbackService = new PlaybackService(
                playerLauncher, history, historyStore, backgroundExecutor, Platform::runLater);

        MediaCenterShell shell = new MediaCenterShell(
                stage,
                settingsRef,
                settingsStore,
                history,
                playbackService,
                platform,
                new MediaScanner(),
                new ArtworkResolver(),
                backgroundExecutor);

        Scene scene = new Scene(shell.node(), INITIAL_WIDTH, INITIAL_HEIGHT);
        applyStylesheet(scene);

        stage.setTitle("Media Center");
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        // Escape is the Back key here, so it must not drop out of full screen.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        stage.setFullScreen(settings.fullScreen());
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();

        shell.installKeyBindings();
        shell.focusCurrentView();

        discoverVlcIfNeeded(platform, shell, settingsRef);
    }

    @Override
    public void stop() {
        LOG.info("Shutting down");
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
        Platform.exit();
    }

    private static void logStartup(PlatformServices platform, Path dataDirectory, ApplicationSettings settings) {
        LOG.log(Level.INFO, () -> "Starting Media Center on " + platform.name()
                + " (Java " + System.getProperty("java.version") + ")");
        LOG.log(Level.INFO, () -> "Application data directory: " + dataDirectory);
        LOG.log(Level.INFO, () -> "Configured VLC: " + settings.vlcPath().map(Path::toString).orElse("<none>"));
        if (settings.mediaRoots().isEmpty()) {
            LOG.info("No media roots configured yet");
        } else {
            for (MediaRoot root : settings.mediaRoots()) {
                LOG.log(Level.INFO, () -> "Media root " + root.displayName()
                        + " [" + root.type() + "] -> " + root.displayPath());
            }
        }
    }

    /** Locates VLC in the background on first run and remembers where it is. */
    private void discoverVlcIfNeeded(
            PlatformServices platform,
            MediaCenterShell shell,
            AtomicReference<ApplicationSettings> settingsRef) {

        if (settingsRef.get().vlcPath().isPresent()) {
            return;
        }
        backgroundExecutor.execute(() -> {
            Optional<Path> found = platform.findVlc();
            if (found.isEmpty()) {
                LOG.warning("VLC was not found automatically; the user must set it in Settings");
                return;
            }
            LOG.log(Level.INFO, () -> "Found VLC at " + found.get());
            Platform.runLater(() -> shell.applySettings(shell.settings().withVlcPath(found)));
        });
    }

    private static void applyStylesheet(Scene scene) {
        var stylesheet = MediaCenterApp.class.getResource(STYLESHEET);
        if (stylesheet == null) {
            LOG.warning("Stylesheet " + STYLESHEET + " is missing from the application image");
            return;
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
    }
}
