package mediacenter.config;

import java.util.Optional;

/**
 * How — and whether — series and movie folders are identified online.
 *
 * <p>Two services take part. An Ollama model turns a ripper's folder name into
 * a searchable title: the endpoint is Ollama's hosted service by default,
 * whose free tier needs an API key, and pointing it at a machine in the house
 * ({@code http://localhost:11434}) needs no key at all. The metadata itself
 * comes from TheTVDB or TMDB — either database serves both series and films,
 * so one key is enough, whichever of the two the user could actually get.
 * With both keys present TheTVDB is used, having been here first.
 *
 * <p>Off by default: scraping sends folder names to services on the internet,
 * which is the user's call to make, not the application's.
 */
public record ScraperSettings(
        boolean enabled,
        Optional<String> tvdbApiKey,
        Optional<String> tmdbApiKey,
        String ollamaEndpoint,
        Optional<String> ollamaApiKey,
        String ollamaModel) {

    /** Where Ollama's hosted service answers; the free tier lives here. */
    public static final String DEFAULT_OLLAMA_ENDPOINT = "https://ollama.com";

    /** A small hosted model; title cleaning does not need a large one. */
    public static final String DEFAULT_OLLAMA_MODEL = "gpt-oss:20b";

    public ScraperSettings {
        tvdbApiKey = normalize(tvdbApiKey);
        tmdbApiKey = normalize(tmdbApiKey);
        ollamaApiKey = normalize(ollamaApiKey);
        ollamaEndpoint = ollamaEndpoint == null || ollamaEndpoint.isBlank()
                ? DEFAULT_OLLAMA_ENDPOINT
                : ollamaEndpoint.trim();
        ollamaModel = ollamaModel == null || ollamaModel.isBlank()
                ? DEFAULT_OLLAMA_MODEL
                : ollamaModel.trim();
    }

    public static ScraperSettings defaults() {
        return new ScraperSettings(
                false, Optional.empty(), Optional.empty(), DEFAULT_OLLAMA_ENDPOINT,
                Optional.empty(), DEFAULT_OLLAMA_MODEL);
    }

    /**
     * Whether a scrape can actually run: switched on, and holding a key to
     * one metadata database or the other. Ollama stays optional — without it
     * the folder name itself is the search term.
     */
    public boolean readyToScrape() {
        return enabled && (tvdbApiKey.isPresent() || tmdbApiKey.isPresent());
    }

    /**
     * Which database a scrape would ask right now — for the Settings screen
     * to say out loud, and defined in exactly one place so the screen and the
     * scraper can never disagree.
     */
    public Optional<String> metadataProviderName() {
        if (tvdbApiKey.isPresent()) {
            return Optional.of("TheTVDB");
        }
        if (tmdbApiKey.isPresent()) {
            return Optional.of("TMDB");
        }
        return Optional.empty();
    }

    /**
     * Whether asking Ollama can possibly work: the hosted service turns away
     * requests without a key, so with no key only a self-chosen endpoint — an
     * Ollama in the house — is worth calling. Skipping a call that can only
     * time out is what keeps a keyless setup fast.
     */
    public boolean ollamaConfigured() {
        return ollamaApiKey.isPresent() || !DEFAULT_OLLAMA_ENDPOINT.equals(ollamaEndpoint);
    }

    public ScraperSettings withEnabled(boolean newEnabled) {
        return new ScraperSettings(
                newEnabled, tvdbApiKey, tmdbApiKey, ollamaEndpoint, ollamaApiKey, ollamaModel);
    }

    public ScraperSettings withTvdbApiKey(Optional<String> newTvdbApiKey) {
        return new ScraperSettings(
                enabled, newTvdbApiKey, tmdbApiKey, ollamaEndpoint, ollamaApiKey, ollamaModel);
    }

    public ScraperSettings withTmdbApiKey(Optional<String> newTmdbApiKey) {
        return new ScraperSettings(
                enabled, tvdbApiKey, newTmdbApiKey, ollamaEndpoint, ollamaApiKey, ollamaModel);
    }

    public ScraperSettings withOllamaEndpoint(String newOllamaEndpoint) {
        return new ScraperSettings(
                enabled, tvdbApiKey, tmdbApiKey, newOllamaEndpoint, ollamaApiKey, ollamaModel);
    }

    public ScraperSettings withOllamaApiKey(Optional<String> newOllamaApiKey) {
        return new ScraperSettings(
                enabled, tvdbApiKey, tmdbApiKey, ollamaEndpoint, newOllamaApiKey, ollamaModel);
    }

    public ScraperSettings withOllamaModel(String newOllamaModel) {
        return new ScraperSettings(
                enabled, tvdbApiKey, tmdbApiKey, ollamaEndpoint, ollamaApiKey, newOllamaModel);
    }

    /** A pasted key arrives with the whitespace the clipboard added. */
    private static Optional<String> normalize(Optional<String> key) {
        if (key == null) {
            return Optional.empty();
        }
        return key.map(String::trim).filter(text -> !text.isEmpty());
    }
}
