package mediacenter.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.JsonException;
import mediacenter.json.JsonFiles;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;

/**
 * Loads and saves {@code config.json}.
 *
 * <p>Loading never throws: an unreadable or corrupt configuration falls back to
 * defaults (the broken file is kept as {@code config.json.corrupt}) so the media
 * center always starts.
 */
public final class SettingsStore {

    private static final Logger LOG = Logger.getLogger(SettingsStore.class.getName());
    private static final String FILE_NAME = "config.json";

    private final Path file;

    public SettingsStore(Path applicationDataDirectory) {
        this.file = applicationDataDirectory.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /** Reads the configuration, falling back to defaults on any problem. */
    public ApplicationSettings load() {
        try {
            Optional<JsonObject> document = JsonFiles.readObject(file);
            if (document.isEmpty()) {
                LOG.log(Level.INFO, () -> "No configuration at " + file + ", starting with defaults");
                return ApplicationSettings.defaults();
            }
            return fromJson(document.get());
        } catch (JsonException e) {
            LOG.log(Level.WARNING, e, () -> "Configuration file " + file + " is not valid JSON");
            JsonFiles.quarantine(file)
                    .ifPresent(target -> LOG.log(Level.WARNING, () -> "Moved invalid configuration to " + target));
            return ApplicationSettings.defaults();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not read configuration file " + file);
            return ApplicationSettings.defaults();
        }
    }

    /** Writes the configuration. Returns false when it could not be persisted. */
    public boolean save(ApplicationSettings settings) {
        try {
            JsonFiles.write(file, toJson(settings));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not write configuration file " + file);
            return false;
        }
    }

    // -- mapping ------------------------------------------------------------

    public static JsonObject toJson(ApplicationSettings settings) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        settings.vlcPath().ifPresent(path -> members.put("vlcPath", new JsonString(path.toString())));
        settings.browserPath().ifPresent(path -> members.put("browserPath", new JsonString(path.toString())));
        settings.ytDlpPath().ifPresent(path -> members.put("ytDlpPath", new JsonString(path.toString())));
        members.put("fullScreen", new JsonBoolean(settings.fullScreen()));
        members.put("theme", new JsonString(settings.theme().name()));
        members.put("slideshowSeconds", new JsonNumber(settings.slideshowSeconds()));
        members.put("playerBufferSeconds", new JsonNumber(settings.playerBufferSeconds()));
        members.put("mirrorGigabytes", new JsonNumber(settings.mirrorGigabytes()));
        members.put("embeddedPlayer", new JsonBoolean(settings.embeddedPlayer()));
        members.put("browserScalePercent", new JsonNumber(settings.browserScalePercent()));

        List<JsonValue> roots = new ArrayList<>();
        for (MediaRoot root : settings.mediaRoots()) {
            Map<String, JsonValue> rootMembers = new LinkedHashMap<>();
            rootMembers.put("id", new JsonString(root.id()));
            rootMembers.put("name", new JsonString(root.displayName()));
            rootMembers.put("path", new JsonString(root.path().toString()));
            rootMembers.put("type", new JsonString(root.type().name()));
            roots.add(new JsonObject(rootMembers));
        }
        members.put("mediaRoots", new JsonArray(roots));

        List<JsonValue> websites = new ArrayList<>();
        for (Website website : settings.websites()) {
            Map<String, JsonValue> websiteMembers = new LinkedHashMap<>();
            websiteMembers.put("id", new JsonString(website.id()));
            websiteMembers.put("name", new JsonString(website.name()));
            websiteMembers.put("url", new JsonString(website.url()));
            websites.add(new JsonObject(websiteMembers));
        }
        members.put("websites", new JsonArray(websites));

        Map<String, JsonValue> scraperMembers = new LinkedHashMap<>();
        scraperMembers.put("enabled", new JsonBoolean(settings.scraper().enabled()));
        settings.scraper().tvdbApiKey()
                .ifPresent(key -> scraperMembers.put("tvdbApiKey", new JsonString(key)));
        scraperMembers.put("ollamaEndpoint", new JsonString(settings.scraper().ollamaEndpoint()));
        settings.scraper().ollamaApiKey()
                .ifPresent(key -> scraperMembers.put("ollamaApiKey", new JsonString(key)));
        scraperMembers.put("ollamaModel", new JsonString(settings.scraper().ollamaModel()));
        members.put("scraper", new JsonObject(scraperMembers));
        return new JsonObject(members);
    }

    public static ApplicationSettings fromJson(JsonObject document) {
        Optional<Path> vlcPath = document.nonBlankString("vlcPath").map(Path::of);
        Optional<Path> browserPath = document.nonBlankString("browserPath").map(Path::of);
        // A hand-written entry: where yt-dlp lives when it is not on the PATH.
        Optional<Path> ytDlpPath = document.nonBlankString("ytDlpPath").map(Path::of);
        boolean fullScreen = document.booleanValue("fullScreen", true);
        Theme theme = document.nonBlankString("theme").flatMap(Theme::parse).orElse(Theme.DARK);
        // JsonValue reads numbers as longs; the interval is small enough to narrow.
        int slideshowSeconds = document.longValue("slideshowSeconds").orElse(5L).intValue();
        int playerBufferSeconds = document.longValue("playerBufferSeconds").orElse(1L).intValue();
        // Absent in files from before the mirror existed; the default applies,
        // because a share too slow to stream should get help without a visit
        // to Settings first.
        int mirrorGigabytes = document.longValue("mirrorGigabytes").orElse(10L).intValue();
        // Absent in every file written before the built-in player existed, and
        // off is the right reading of those files.
        boolean embeddedPlayer = document.booleanValue("embeddedPlayer", false);
        // 150 for files that predate the setting: the whole point of a website
        // tile is a desktop page readable from a sofa.
        int browserScalePercent = document.longValue("browserScalePercent").orElse(150L).intValue();

        List<MediaRoot> roots = new ArrayList<>();
        for (JsonObject rootDocument : document.objectArray("mediaRoots")) {
            readRoot(rootDocument).ifPresent(roots::add);
        }
        List<Website> websites = new ArrayList<>();
        for (JsonObject websiteDocument : document.objectArray("websites")) {
            readWebsite(websiteDocument).ifPresent(websites::add);
        }
        return new ApplicationSettings(
                vlcPath, browserPath, ytDlpPath, fullScreen, theme, roots, slideshowSeconds, playerBufferSeconds,
                mirrorGigabytes, embeddedPlayer, websites, browserScalePercent, readScraper(document));
    }

    /** Absent in every file written before the scraper existed; defaults are off. */
    private static ScraperSettings readScraper(JsonObject document) {
        Optional<JsonValue> value = document.get("scraper");
        if (value.isEmpty() || !(value.get() instanceof JsonObject scraper)) {
            return ScraperSettings.defaults();
        }
        return new ScraperSettings(
                scraper.booleanValue("enabled", false),
                scraper.nonBlankString("tvdbApiKey"),
                scraper.nonBlankString("ollamaEndpoint").orElse(ScraperSettings.DEFAULT_OLLAMA_ENDPOINT),
                scraper.nonBlankString("ollamaApiKey"),
                scraper.nonBlankString("ollamaModel").orElse(ScraperSettings.DEFAULT_OLLAMA_MODEL));
    }

    private static Optional<MediaRoot> readRoot(JsonObject document) {
        Optional<String> path = document.nonBlankString("path");
        if (path.isEmpty()) {
            LOG.warning("Ignoring a configured media root without a path");
            return Optional.empty();
        }
        // "displayName" is accepted as an alias so a hand-written file matching the
        // specification's field names is understood as well.
        String name = document.nonBlankString("name")
                .or(() -> document.nonBlankString("displayName"))
                .orElse(path.get());
        String id = document.nonBlankString("id").orElseGet(() -> UUID.randomUUID().toString());
        MediaRootType type = document.nonBlankString("type")
                .flatMap(MediaRootType::parse)
                .orElse(MediaRootType.GENERAL);
        return Optional.of(new MediaRoot(id, name, Path.of(path.get()), type));
    }

    private static Optional<Website> readWebsite(JsonObject document) {
        Optional<String> url = document.nonBlankString("url");
        if (url.isEmpty()) {
            LOG.warning("Ignoring a configured website without an address");
            return Optional.empty();
        }
        String name = document.nonBlankString("name").orElse(url.get());
        String id = document.nonBlankString("id").orElseGet(() -> UUID.randomUUID().toString());
        return Optional.of(new Website(id, name, url.get()));
    }
}
