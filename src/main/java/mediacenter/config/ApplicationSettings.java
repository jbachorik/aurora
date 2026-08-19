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
        int slideshowSeconds,
        int playerBufferSeconds,
        boolean embeddedPlayer,
        List<Website> websites,
        int browserScalePercent) {

    public ApplicationSettings {
        vlcPath = vlcPath == null ? Optional.empty() : vlcPath;
        browserPath = browserPath == null ? Optional.empty() : browserPath;
        theme = theme == null ? Theme.DARK : theme;
        mediaRoots = mediaRoots == null ? List.of() : List.copyOf(mediaRoots);
        websites = websites == null ? List.of() : List.copyOf(websites);
        // Under two seconds nobody can take the picture in; over a minute the
        // screen looks stuck.
        slideshowSeconds = Math.clamp(slideshowSeconds, 2, 60);
        // How much the player is asked to read ahead before it starts. Sixty is
        // the most VLC's caching options accept; zero means say nothing at all
        // and leave whatever the player does by itself.
        playerBufferSeconds = Math.clamp(playerBufferSeconds, 0, 60);
        // 100 is "as the browser would"; past 300 a single headline fills the
        // screen. The hint exists so a desktop page reads from a sofa.
        browserScalePercent = Math.clamp(browserScalePercent, 100, 300);
    }

    public static ApplicationSettings defaults() {
        return new ApplicationSettings(
                Optional.empty(), Optional.empty(), true, Theme.DARK, List.of(), 5, 1, false,
                List.of(), 150);
    }

    public ApplicationSettings withVlcPath(Optional<Path> newVlcPath) {
        return new ApplicationSettings(
                newVlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withBrowserPath(Optional<Path> newBrowserPath) {
        return new ApplicationSettings(
                vlcPath, newBrowserPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withFullScreen(boolean newFullScreen) {
        return new ApplicationSettings(
                vlcPath, browserPath, newFullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withTheme(Theme newTheme) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, newTheme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withMediaRoots(List<MediaRoot> newMediaRoots) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, newMediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withSlideshowSeconds(int newSlideshowSeconds) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, mediaRoots, newSlideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    public ApplicationSettings withPlayerBufferSeconds(int newPlayerBufferSeconds) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds, newPlayerBufferSeconds, embeddedPlayer, websites, browserScalePercent);
    }

    /**
     * Whether films play inside this window (libVLC drawing into the page)
     * rather than in a VLC window of their own. Off by default: the external
     * player is the long-proven path, and the built-in one needs a loadable
     * libVLC to exist at all.
     */
    public ApplicationSettings withEmbeddedPlayer(boolean newEmbeddedPlayer) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, newEmbeddedPlayer, websites, browserScalePercent);
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

    // -- websites ------------------------------------------------------------

    public ApplicationSettings withWebsites(List<Website> newWebsites) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, newWebsites, browserScalePercent);
    }

    /** Adds a website tile, replacing any existing one with the same id. */
    public ApplicationSettings withWebsite(Website website) {
        List<Website> updated = new ArrayList<>();
        boolean replaced = false;
        for (Website existing : websites) {
            if (existing.id().equals(website.id())) {
                updated.add(website);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(website);
        }
        return withWebsites(updated);
    }

    public ApplicationSettings withoutWebsite(String websiteId) {
        return withWebsites(websites.stream().filter(site -> !site.id().equals(websiteId)).toList());
    }

    /** How much larger a website tile asks the browser to draw everything. */
    public ApplicationSettings withBrowserScalePercent(int newBrowserScalePercent) {
        return new ApplicationSettings(
                vlcPath, browserPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, embeddedPlayer, websites, newBrowserScalePercent);
    }
}
