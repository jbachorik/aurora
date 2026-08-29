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
     * Closes the kiosk browser and returns to the main menu.
     *
     * @return false when there was nothing to stop
     */
    boolean stopBrowser();

    /** The address currently open in the kiosk browser, if any. */
    Optional<String> currentUrl();
}
