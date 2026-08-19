package mediacenter.ui;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

/**
 * Runs blocking work off the JavaFX thread and delivers the outcome back on it.
 *
 * <p>Everything that touches a disk or a network share goes through here.
 */
public final class FxTasks {

    private static final Logger LOG = Logger.getLogger(FxTasks.class.getName());

    /** Work that may fail with a checked exception. */
    @FunctionalInterface
    public interface BackgroundWork<T> {
        T run() throws Exception;
    }

    private FxTasks() {
    }

    /**
     * Executes {@code work} on {@code executor} and calls exactly one of the two
     * callbacks on the JavaFX application thread.
     */
    public static <T> void run(
            Executor executor,
            BackgroundWork<T> work,
            Consumer<T> onSuccess,
            Consumer<Exception> onFailure) {

        Runnable task = () -> {
            try {
                T value = work.run();
                onFx(() -> onSuccess.accept(value));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.FINE, "Background task failed", e);
                onFx(() -> onFailure.accept(e));
            }
        };
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The executor only refuses while the application is shutting down,
            // and work refused at shutdown has nobody left to report to.
            LOG.log(Level.FINE, "Background task refused during shutdown", e);
        }
    }

    /** Runs an action on the JavaFX thread, immediately when already on it. */
    public static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
