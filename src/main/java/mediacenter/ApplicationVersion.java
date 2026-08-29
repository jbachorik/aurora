package mediacenter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application's own version, so the home screen can tell the viewer
 * which build they are looking at.
 *
 * <p>Read from a bundled resource rather than the jar manifest: jlink
 * assembles the packaged runtime image module by module and does not carry a
 * module's {@code META-INF} into it, so by the time the application is
 * actually distributed there is no manifest left to read
 * "Implementation-Version" back from. The resource is written at build time
 * by the {@code generateVersionResource} Gradle task and, being an ordinary
 * part of the module's content, survives linking.
 */
public final class ApplicationVersion {

    private static final Logger LOG = Logger.getLogger(ApplicationVersion.class.getName());

    /** Shown when the resource is missing — running straight from an IDE, say. */
    private static final String UNKNOWN = "dev";

    private ApplicationVersion() {
    }

    /** The application's version, or {@code "dev"} when it cannot be determined. */
    public static String current() {
        try (InputStream in = ApplicationVersion.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", UNKNOWN);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read the application version", e);
            return UNKNOWN;
        }
    }
}
