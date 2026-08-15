/**
 * Lightweight media-center frontend.
 *
 * <p>Only the modules that are genuinely used are required. In particular
 * {@code javafx.media} is deliberately absent: playback is delegated to an
 * external VLC process, this application never decodes media itself.
 */
module media.center {
    requires javafx.controls;
    requires java.logging;

    // JavaFX instantiates the Application subclass reflectively; nothing else
    // needs access to this module.
    exports mediacenter to javafx.graphics;
}
