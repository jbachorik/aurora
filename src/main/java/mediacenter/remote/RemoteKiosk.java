package mediacenter.remote;

import java.util.Optional;

/**
 * What the remote-control server may ask the running media center to do.
 *
 * <p>Implemented by the shell; every method is called from an HTTP worker
 * thread, so implementations must be safe to invoke off the JavaFX thread.
 */
public interface RemoteKiosk {

    /**
     * Opens the address full screen in the kiosk browser, replacing whatever
     * the browser is already showing.
     *
     * @return the complaint when the request cannot be honoured (no browser
     *         configured, blank address), empty when the launch is under way
     */
    Optional<String> openUrl(String url);

    /**
     * Resolves the page's video stream with yt-dlp and plays it in the media
     * center's own player, closing the kiosk browser on the way. Blocks for
     * the resolution — up to tens of seconds — so the caller can answer with
     * the outcome; the playback itself runs on after the return.
     *
     * @return the complaint when nothing will play (no VLC, no yt-dlp, or a
     *         page with no extractable stream), empty when playback is starting
     */
    Optional<String> watchUrl(String url);

    /**
     * Closes the kiosk browser and returns to the main menu.
     *
     * @return false when there was nothing to stop
     */
    boolean stopBrowser();

    /** The address currently open in the kiosk browser, if any. */
    Optional<String> currentUrl();
}
