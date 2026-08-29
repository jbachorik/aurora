package mediacenter.ui;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import mediacenter.config.ApplicationSettings;
import mediacenter.history.PlaybackHistory;
import mediacenter.history.WatchedService;
import mediacenter.media.ArtworkResolver;
import mediacenter.media.MediaScanner;
import mediacenter.platform.PlatformServices;
import mediacenter.scrape.ScrapeService;
import mediacenter.ui.components.ArtworkCache;

/** The collaborators every view needs, passed explicitly rather than injected. */
public record UiContext(
        Navigation navigation,
        Supplier<ApplicationSettings> settings,
        Executor backgroundExecutor,
        MediaScanner scanner,
        ArtworkResolver artworkResolver,
        ArtworkCache artworkCache,
        PlaybackHistory history,
        WatchedService watched,
        PlatformServices platform,
        ScrapeService scrapeService) {
}
