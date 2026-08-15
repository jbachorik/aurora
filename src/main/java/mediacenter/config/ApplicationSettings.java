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
        List<MediaRoot> mediaRoots) {

    public ApplicationSettings {
        vlcPath = vlcPath == null ? Optional.empty() : vlcPath;
        browserPath = browserPath == null ? Optional.empty() : browserPath;
        mediaRoots = mediaRoots == null ? List.of() : List.copyOf(mediaRoots);
    }

    public static ApplicationSettings defaults() {
        return new ApplicationSettings(Optional.empty(), Optional.empty(), true, List.of());
    }

    public ApplicationSettings withVlcPath(Optional<Path> newVlcPath) {
        return new ApplicationSettings(newVlcPath, browserPath, fullScreen, mediaRoots);
    }

    public ApplicationSettings withBrowserPath(Optional<Path> newBrowserPath) {
        return new ApplicationSettings(vlcPath, newBrowserPath, fullScreen, mediaRoots);
    }

    public ApplicationSettings withFullScreen(boolean newFullScreen) {
        return new ApplicationSettings(vlcPath, browserPath, newFullScreen, mediaRoots);
    }

    public ApplicationSettings withMediaRoots(List<MediaRoot> newMediaRoots) {
        return new ApplicationSettings(vlcPath, browserPath, fullScreen, newMediaRoots);
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
