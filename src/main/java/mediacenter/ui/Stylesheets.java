package mediacenter.ui;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.scene.Scene;

import mediacenter.config.Theme;

/**
 * Applies the stylesheets: one palette for the selected {@link Theme}, followed
 * by the layout rules that reference it.
 *
 * <p>Splitting them this way means switching theme replaces a few dozen colour
 * definitions rather than duplicating every rule.
 */
public final class Stylesheets {

    private static final Logger LOG = Logger.getLogger(Stylesheets.class.getName());
    private static final String LAYOUT = "/mediacenter/ui/mediacenter.css";

    private Stylesheets() {
    }

    /** Replaces the scene's stylesheets with the ones for this theme. */
    public static void apply(Scene scene, Theme theme) {
        List<String> stylesheets = List.of(theme.stylesheet(), LAYOUT).stream()
                .map(Stylesheets::resolve)
                .filter(java.util.Objects::nonNull)
                .toList();
        scene.getStylesheets().setAll(stylesheets);
    }

    private static String resolve(String resource) {
        var url = Stylesheets.class.getResource(resource);
        if (url == null) {
            LOG.log(Level.WARNING, () -> "Stylesheet " + resource + " is missing from the application image");
            return null;
        }
        return url.toExternalForm();
    }
}
