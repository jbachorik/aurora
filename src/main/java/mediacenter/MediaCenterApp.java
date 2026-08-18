package mediacenter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.stage.Screen;
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
import mediacenter.ui.SceneSnapshot;
import mediacenter.ui.Stylesheets;

/**
 * JavaFX entry point.
 *
 * <p>Startup shows the window first and does everything that can block —
 * scanning, artwork, VLC discovery — afterwards on background threads, so an
 * offline NAS can never delay the home screen.
 */
public final class MediaCenterApp extends Application {

    private static final Logger LOG = Logger.getLogger(MediaCenterApp.class.getName());

    /** Starts in a window even when the configuration says full screen. */
    static final String WINDOWED_ARGUMENT = "--windowed";

    /** Window size on a display with room for it; a smaller screen gets what it has. */
    private static final double PREFERRED_WIDTH = 1600;
    private static final double PREFERRED_HEIGHT = 900;

    /**
     * Smallest window the layout is designed for, clamped to the screen before it
     * is used: a minimum taller than the display cannot be honoured, and whatever
     * does not fit is drawn past the edge where nothing can reach it.
     */
    private static final double MINIMUM_WIDTH = 960;
    private static final double MINIMUM_HEIGHT = 600;

    /** Window-manager icon sizes, small first: a taskbar asks for 16 and 32. */
    private static final List<Integer> ICON_SIZES = List.of(16, 32, 48, 64, 128, 256);

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
        // Escape hatch for the target machine: the media center owns the whole
        // screen by default, and there is no console to read on a TV if that
        // ever misbehaves.
        boolean startFullScreen = settings.fullScreen() && !getParameters().getRaw().contains(WINDOWED_ARGUMENT);
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

        // Taken from the display rather than from a constant: a 1600x900 window
        // asked for on a smaller screen is granted one that hangs off the bottom,
        // and every control below the fold goes with it.
        Rectangle2D visible = Screen.getPrimary().getVisualBounds();
        double width = Math.min(PREFERRED_WIDTH, visible.getWidth());
        double height = Math.min(PREFERRED_HEIGHT, visible.getHeight());

        Scene scene = new Scene(shell.node(), width, height);
        Stylesheets.apply(scene, settings.theme());

        stage.setTitle("Media Center");
        applicationIcons(stage);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(MINIMUM_WIDTH, visible.getWidth()));
        stage.setMinHeight(Math.min(MINIMUM_HEIGHT, visible.getHeight()));
        // Escape is the Back key here, so it must not drop out of full screen.
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        stage.setFullScreenExitHint("");
        stage.setFullScreen(startFullScreen);
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();

        shell.attachToScene();
        shell.focusCurrentView();

        SceneSnapshot.scheduleIfRequested(scene, getParameters().getRaw());

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

    /**
     * Several sizes rather than one: the window manager picks the closest match, so
     * handing it only the large image leaves a taskbar to downscale 256px into 16,
     * which smears. A missing icon is never fatal — the window opens without one.
     */
    private static void applicationIcons(Stage stage) {
        for (int size : ICON_SIZES) {
            String resource = "/mediacenter/ui/icon/aurora-" + size + ".png";
            try (InputStream stream = MediaCenterApp.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    LOG.log(Level.WARNING, () -> "Application icon missing from the image: " + resource);
                    continue;
                }
                stage.getIcons().add(new Image(stream));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not load application icon " + resource, e);
            }
        }
    }

    private static void logStartup(PlatformServices platform, Path dataDirectory, ApplicationSettings settings) {
        LOG.log(Level.INFO, () -> "Starting Media Center on " + platform.name()
                + " (Java " + System.getProperty("java.version") + ")");
        LOG.log(Level.INFO, () -> "Application data directory: " + dataDirectory);
        LOG.log(Level.INFO, () -> "Configured VLC: " + settings.vlcPath().map(Path::toString).orElse("<none>"));
        LOG.log(Level.INFO, () -> "Theme: " + settings.theme()
                + ", full screen: " + settings.fullScreen());
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

}
