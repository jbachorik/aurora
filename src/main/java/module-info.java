/**
 * Lightweight media-center frontend.
 *
 * <p>Only the modules that are genuinely used are required. In particular
 * {@code javafx.media} is deliberately absent: playback is either delegated to
 * an external VLC process or — when the built-in player is switched on —
 * decoded by libVLC itself, bound through {@code java.lang.foreign} in
 * {@code java.base}. Either way, no Java media stack is involved.
 */
module media.center {
    requires javafx.controls;
    requires java.logging;
    // The remote control's HTTP server; one platform module, no dependency.
    requires jdk.httpserver;

    // JavaFX instantiates the Application subclass reflectively; nothing else
    // needs access to this module.
    exports mediacenter to javafx.graphics;
}
