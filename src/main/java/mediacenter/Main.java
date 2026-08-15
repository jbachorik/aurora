package mediacenter;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;

import mediacenter.platform.PlatformServices;

/**
 * Program entry point.
 *
 * <p>Configures logging before JavaFX starts so that even a failure during
 * startup ends up in {@code logs/application.log}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path logDirectory = PlatformServices.detect().applicationDataDirectory().resolve("logs");
        Path logFile = Logging.configure(logDirectory);

        Logger log = Logger.getLogger(Main.class.getName());
        if (logFile != null) {
            log.log(Level.INFO, () -> "Logging to " + logFile);
        }

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.log(Level.SEVERE, "Uncaught exception on thread " + thread.getName(), throwable));

        Application.launch(MediaCenterApp.class, args);
    }
}
