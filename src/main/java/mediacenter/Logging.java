package mediacenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Sets up {@code java.util.logging} with a rotating file in the application data
 * directory. Technical detail belongs in this file — never on the TV screen.
 */
public final class Logging {

    private static final String LOG_FILE_NAME = "application.log";
    private static final int LOG_FILE_LIMIT_BYTES = 1024 * 1024;
    private static final int LOG_FILE_COUNT = 3;

    private Logging() {
    }

    /**
     * Configures the root logger.
     *
     * @param logDirectory directory to write {@code application.log} into
     * @return the log file, when file logging could be set up
     */
    public static Path configure(Path logDirectory) {
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            root.removeHandler(handler);
        }
        root.setLevel(Level.INFO);

        Formatter formatter = new SingleLineFormatter();

        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(Level.INFO);
        root.addHandler(console);

        try {
            Files.createDirectories(logDirectory);
            Path logFile = logDirectory.resolve(LOG_FILE_NAME);
            FileHandler fileHandler = new FileHandler(
                    logFile.toString(), LOG_FILE_LIMIT_BYTES, LOG_FILE_COUNT, true);
            fileHandler.setFormatter(formatter);
            fileHandler.setLevel(Level.INFO);
            root.addHandler(fileHandler);
            return logFile;
        } catch (IOException | SecurityException e) {
            root.log(Level.WARNING, "File logging is unavailable, logging to the console only", e);
            return null;
        }
    }

    /** Compact one-line records; stack traces are appended when present. */
    private static final class SingleLineFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            StringBuilder out = new StringBuilder(160);
            out.append(String.format("%1$tF %1$tT.%1$tL", new Date(record.getMillis())))
                    .append(' ')
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(' ')
                    .append(shortLoggerName(record.getLoggerName()))
                    .append(" - ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                out.append(stackTrace(record.getThrown()));
            }
            return out.toString();
        }

        private static String shortLoggerName(String loggerName) {
            if (loggerName == null) {
                return "?";
            }
            int lastDot = loggerName.lastIndexOf('.');
            return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
        }

        private static String stackTrace(Throwable thrown) {
            java.io.StringWriter writer = new java.io.StringWriter();
            try (java.io.PrintWriter printer = new java.io.PrintWriter(writer)) {
                thrown.printStackTrace(printer);
            }
            return writer.toString();
        }
    }
}
