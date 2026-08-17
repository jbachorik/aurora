package mediacenter.ui;

import java.nio.file.Path;
import java.util.List;

import mediacenter.media.MediaItem;
import mediacenter.media.MediaRoot;

/** What a view can ask the shell to do. */
public interface Navigation {

    /** Opens a chooser listing the given roots, or the root itself when there is only one. */
    void openRoots(String title, List<MediaRoot> roots);

    /** Browses a folder inside a root. */
    void browse(MediaRoot root, Path folder);

    void openSettings();

    /** Minimizes the media center and launches the configured browser, if any. */
    void openBrowserOrDesktop();

    /** Hides the UI, plays the file with the external player and comes back afterwards. */
    void play(MediaItem item);

    /** Plays a file that is not part of the current listing (e.g. from history). */
    void play(Path mediaFile, String displayTitle);

    /** Runs every photograph beneath a folder as a slideshow. */
    void openSlideshow(Path folder);

    /** Opens one photograph, with the arrows moving through its own folder. */
    void openPhoto(Path folder, Path photo);

    void goBack();

    void goHome();

    void exitApplication();

    /** Shows a transient, friendly error. */
    void showError(String message);

    /** Shows a transient, neutral message. */
    void showInfo(String message);
}
