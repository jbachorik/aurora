package mediacenter.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;

/**
 * Everything the user can configure. Immutable; the UI replaces the whole
 * value and hands it back to {@link SettingsStore}.
 */
public record ApplicationSettings(
        Optional<Path> vlcPath,
        Optional<Path> browserPath,
        boolean fullScreen,
        Theme theme,
        List<MediaRoot> mediaRoots,
        int slideshowSeconds) {

    public ApplicationSettings {
        vlcPath = vlcPath == null ? Optional.empty() : vlcPath;
        browserPath = browserPath == null ? Optional.empty() : browserPath;
        theme = theme == null ? Theme.DARK : theme;
        mediaRoots = mediaRoots == null ? List.of() : List.copyOf(mediaRoots);
        // Under two seconds nobody can take the picture in; over a minute the
        // screen looks stuck.
        slideshowSeconds = Math.clamp(slideshowSeconds, 2, 60);
    }

    public static ApplicationSettings defaults() {
        return new ApplicationSettings(Optional.empty(), Optional.empty(), true, Theme.DARK, List.of(), 5);
    }

    public ApplicationSettings withVlcPath(Optional<Path> newVlcPath) {
        return new ApplicationSettings(newVlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds);
    }

    public ApplicationSettings withBrowserPath(Optional<Path> newBrowserPath) {
        return new ApplicationSettings(vlcPath, newBrowserPath, fullScreen, theme, mediaRoots, slideshowSeconds);
    }

    public ApplicationSettings withFullScreen(boolean newFullScreen) {
        return new ApplicationSettings(vlcPath, browserPath, newFullScreen, theme, mediaRoots, slideshowSeconds);
    }

    public ApplicationSettings withTheme(Theme newTheme) {
        return new ApplicationSettings(vlcPath, browserPath, fullScreen, newTheme, mediaRoots, slideshowSeconds);
    }

    public ApplicationSettings withMediaRoots(List<MediaRoot> newMediaRoots) {
        return new ApplicationSettings(vlcPath, browserPath, fullScreen, theme, newMediaRoots, slideshowSeconds);
    }

    public ApplicationSettings withSlideshowSeconds(int newSlideshowSeconds) {
        return new ApplicationSettings(vlcPath, browserPath, fullScreen, theme, mediaRoots, newSlideshowSeconds);
    }

    /** Adds a root, replacing any existing root with the same id. */
    public ApplicationSettings withRoot(MediaRoot root) {
        List<MediaRoot> updated = new ArrayList<>();
        boolean replaced = false;
        for (MediaRoot existing : mediaRoots) {
            if (existing.id().equals(root.id())) {
                updated.add(root);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(root);
        }
        return withMediaRoots(updated);
    }

    public ApplicationSettings withoutRoot(String rootId) {
        return withMediaRoots(mediaRoots.stream().filter(root -> !root.id().equals(rootId)).toList());
    }

    public List<MediaRoot> rootsOfType(MediaRootType type) {
        return mediaRoots.stream().filter(root -> root.type() == type).toList();
    }

    public Optional<MediaRoot> rootById(String rootId) {
        return mediaRoots.stream().filter(root -> root.id().equals(rootId)).findFirst();
    }
}
