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
        Optional<Path> ytDlpPath,
        boolean fullScreen,
        Theme theme,
        List<MediaRoot> mediaRoots,
        int slideshowSeconds,
        int playerBufferSeconds,
        int mirrorGigabytes,
        boolean embeddedPlayer,
        List<Website> websites,
        int browserScalePercent,
        ScraperSettings scraper) {

    public ApplicationSettings {
        vlcPath = vlcPath == null ? Optional.empty() : vlcPath;
        browserPath = browserPath == null ? Optional.empty() : browserPath;
        ytDlpPath = ytDlpPath == null ? Optional.empty() : ytDlpPath;
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
        // Disk given to local copies of network media. Zero switches the mirror
        // off; the ceiling only guards against a typo in a hand-edited file.
        mirrorGigabytes = Math.clamp(mirrorGigabytes, 0, 500);
        // 100 is "as the browser would"; past 300 a single headline fills the
        // screen. The hint exists so a desktop page reads from a sofa.
        browserScalePercent = Math.clamp(browserScalePercent, 100, 300);
        scraper = scraper == null ? ScraperSettings.defaults() : scraper;
    }

    public static ApplicationSettings defaults() {
        return new ApplicationSettings(
                Optional.empty(), Optional.empty(), Optional.empty(), true, Theme.DARK, List.of(),
                5, 1, 10, false, List.of(), 150, ScraperSettings.defaults());
    }

    public ApplicationSettings withVlcPath(Optional<Path> newVlcPath) {
        return new ApplicationSettings(
                newVlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withBrowserPath(Optional<Path> newBrowserPath) {
        return new ApplicationSettings(
                vlcPath, newBrowserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    /**
     * Where yt-dlp lives, when the user has pointed at one by hand — a
     * {@code config.json} entry, with no Settings row yet. Absent means look
     * for it on the PATH and in the usual install spots.
     */
    public ApplicationSettings withYtDlpPath(Optional<Path> newYtDlpPath) {
        return new ApplicationSettings(
                vlcPath, browserPath, newYtDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withFullScreen(boolean newFullScreen) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, newFullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withTheme(Theme newTheme) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, newTheme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withMediaRoots(List<MediaRoot> newMediaRoots) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, newMediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withSlideshowSeconds(int newSlideshowSeconds) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, newSlideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    public ApplicationSettings withPlayerBufferSeconds(int newPlayerBufferSeconds) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, newPlayerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    /**
     * How much disk the local media mirror may take for copies of network
     * files — the buffer-ahead cache before a playback and the permanent
     * copies of frequently played titles. Zero turns both off.
     */
    public ApplicationSettings withMirrorGigabytes(int newMirrorGigabytes) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, newMirrorGigabytes, embeddedPlayer, websites, browserScalePercent, scraper);
    }

    /**
     * Whether films play inside this window (libVLC drawing into the page)
     * rather than in a VLC window of their own. Off by default: the external
     * player is the long-proven path, and the built-in one needs a loadable
     * libVLC to exist at all.
     */
    public ApplicationSettings withEmbeddedPlayer(boolean newEmbeddedPlayer) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, newEmbeddedPlayer, websites, browserScalePercent, scraper);
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
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, newWebsites, browserScalePercent, scraper);
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
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, newBrowserScalePercent, scraper);
    }

    /** How — and whether — series and movie folders are identified online. */
    public ApplicationSettings withScraper(ScraperSettings newScraper) {
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, mediaRoots, slideshowSeconds, playerBufferSeconds, mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, newScraper);
    }
}
