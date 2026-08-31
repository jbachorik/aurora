package mediacenter.ui;

import java.nio.file.Path;
import java.util.List;

import mediacenter.config.Website;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;

/** What a view can ask the shell to do. */
public interface Navigation {

    /** Opens a chooser listing the given roots, or the root itself when there is only one. */
    void openRoots(String title, List<MediaRoot> roots);

    /** Browses a folder inside a root. */
    void browse(MediaRoot root, Path folder);

    void openSettings();

    /**
     * Hides the UI, opens a website tile full screen in the configured browser
     * and comes back when the browser exits — playback, with a page for a film.
     */
    void openWebsite(Website website);

    /** Hides the UI, plays the file with the external player and comes back afterwards. */
    void play(MediaItem item);

    /**
     * Like {@link #play(MediaItem)}, with the given items queued to follow when
     * the player runs off the end of each — a run of episodes. Closing the
     * player abandons whatever is left of the queue.
     */
    void play(MediaItem item, List<MediaItem> playOnwards);

    /** Plays a file that is not part of the current listing (e.g. from history). */
    void play(Path mediaFile, String displayTitle);

    /** Runs every photograph beneath a folder as a slideshow. */
    void openSlideshow(Path folder);

    /** Opens one photograph, with the arrows moving through its own folder. */
    void openPhoto(Path folder, Path photo);

    /** Puts the computer to sleep, leaving the media center running for the waking. */
    void sleepComputer();

    void goBack();

    void goHome();

    void exitApplication();

    /**
     * Re-reads the current page's title and subtitle into the header — for a
     * page whose title just got better, such as a folder the scraper has now
     * identified.
     */
    void refreshHeader();

    /** Shows a transient, friendly error. */
    void showError(String message);

    /** Shows a transient, neutral message. */
    void showInfo(String message);
}
