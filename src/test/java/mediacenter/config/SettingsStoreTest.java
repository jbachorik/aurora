package mediacenter.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;

class SettingsStoreTest {

    @Test
    void defaultsAreUsedWhenNothingHasBeenSavedYet(@TempDir Path temp) {
        ApplicationSettings settings = new SettingsStore(temp).load();

        assertEquals(Optional.empty(), settings.vlcPath());
        assertTrue(settings.mediaRoots().isEmpty());
        assertTrue(settings.fullScreen());
    }

    @Test
    @DisplayName("the playback buffer survives a save and load, and an absent one is VLC's default")
    void keepsThePlaybackBuffer(@TempDir Path temp) throws IOException {
        SettingsStore store = new SettingsStore(temp);
        store.save(ApplicationSettings.defaults().withPlayerBufferSeconds(10));

        assertEquals(10, store.load().playerBufferSeconds());

        Files.writeString(temp.resolve("config.json"), "{\"theme\":\"DARK\"}");
        assertEquals(1, store.load().playerBufferSeconds());
    }

    @Test
    @DisplayName("a media center defaults to the dark theme")
    void theDefaultThemeIsDark(@TempDir Path temp) {
        assertEquals(Theme.DARK, new SettingsStore(temp).load().theme());
        assertEquals(Theme.DARK, ApplicationSettings.defaults().theme());
    }

    @Test
    void anUnknownOrMissingThemeFallsBackToDark(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("config.json"), "{\"theme\":\"SEPIA\"}");
        assertEquals(Theme.DARK, new SettingsStore(temp).load().theme());

        Files.writeString(temp.resolve("config.json"), "{\"fullScreen\":true}");
        assertEquals(Theme.DARK, new SettingsStore(temp).load().theme());
    }

    @Test
    void themeParsingIsLenient() {
        assertEquals(Optional.of(Theme.LIGHT), Theme.parse(" light "));
        assertEquals(Optional.of(Theme.DARK), Theme.parse("DARK"));
        assertEquals(Optional.empty(), Theme.parse("sepia"));
        assertEquals(Optional.empty(), Theme.parse(null));
    }

    @Test
    @DisplayName("settings survive a save/load round trip, UNC paths included")
    void roundTripsSettings(@TempDir Path temp) {
        SettingsStore store = new SettingsStore(temp);
        MediaRoot movies = MediaRoot.create(
                "Movies", Path.of("\\\\synology\\video\\Movies"), MediaRootType.MOVIES);
        MediaRoot tv = MediaRoot.create("TV", Path.of("D:\\Media\\TV"), MediaRootType.TV);
        ApplicationSettings original = new ApplicationSettings(
                Optional.of(Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe")),
                Optional.of(Path.of("C:\\Program Files\\Mozilla Firefox\\firefox.exe")),
                Optional.of(Path.of("C:\\Tools\\yt-dlp.exe")),
                false,
                Theme.LIGHT,
                List.of(movies, tv),
                5,
                10,
                25,
                true,
                List.of(new Website("mosfilm", "Mosfilm", "https://cinema.mosfilm.ru")),
                200,
                new ScraperSettings(
                        true, Optional.of("tvdb-key"), Optional.of("tmdb-key"),
                        "http://localhost:11434", Optional.empty(), "llama3.2"));

        assertTrue(store.save(original));
        ApplicationSettings reloaded = store.load();

        assertEquals(original.vlcPath(), reloaded.vlcPath());
        assertEquals(original.browserPath(), reloaded.browserPath());
        assertEquals(original.ytDlpPath(), reloaded.ytDlpPath());
        assertFalse(reloaded.fullScreen());
        assertEquals(Theme.LIGHT, reloaded.theme());
        assertEquals(2, reloaded.mediaRoots().size());
        assertEquals(movies, reloaded.mediaRoots().getFirst());
        assertEquals("\\\\synology\\video\\Movies", reloaded.mediaRoots().getFirst().displayPath());
        assertEquals(10, reloaded.playerBufferSeconds());
        assertEquals(25, reloaded.mirrorGigabytes());
        assertTrue(reloaded.embeddedPlayer());
        assertEquals(original.websites(), reloaded.websites());
        assertEquals(200, reloaded.browserScalePercent());
        assertEquals(original.scraper(), reloaded.scraper());
    }

    @Test
    @DisplayName("a configuration written before the scraper existed loads with it off")
    void defaultsTheScraperWhenTheKeyIsAbsent(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("config.json"), "{\"fullScreen\": true, \"theme\": \"DARK\"}");

        ScraperSettings scraper = new SettingsStore(temp).load().scraper();

        assertEquals(ScraperSettings.defaults(), scraper);
        assertFalse(scraper.enabled());
        assertFalse(scraper.readyToScrape());
    }

    @Test
    @DisplayName("switched on with a TheTVDB key is what makes the scraper ready")
    void theScraperNeedsItsSwitchAndTheTvdbKey(@TempDir Path temp) {
        ScraperSettings offButKeyed = ScraperSettings.defaults()
                .withTvdbApiKey(Optional.of("tvdb-key"));
        assertFalse(offButKeyed.readyToScrape());
        assertTrue(offButKeyed.withEnabled(true).readyToScrape());
        assertFalse(ScraperSettings.defaults().withEnabled(true).readyToScrape());

        SettingsStore store = new SettingsStore(temp);
        assertTrue(store.save(ApplicationSettings.defaults()
                .withScraper(offButKeyed.withEnabled(true))));
        assertTrue(store.load().scraper().readyToScrape());
    }

    @Test
    @DisplayName("a TMDB key alone is as good as a TheTVDB key; with both, TheTVDB answers")
    void eitherMetadataKeyReadiesTheScraper() {
        ScraperSettings tmdbOnly = ScraperSettings.defaults()
                .withEnabled(true)
                .withTmdbApiKey(Optional.of("tmdb-key"));
        assertTrue(tmdbOnly.readyToScrape());
        assertEquals(Optional.of("TMDB"), tmdbOnly.metadataProviderName());

        ScraperSettings both = tmdbOnly.withTvdbApiKey(Optional.of("tvdb-key"));
        assertEquals(Optional.of("TheTVDB"), both.metadataProviderName());
        assertEquals(Optional.empty(), ScraperSettings.defaults().metadataProviderName());
    }

    @Test
    @DisplayName("the TMDB key survives a save and load")
    void roundTripsTheTmdbKey(@TempDir Path temp) {
        SettingsStore store = new SettingsStore(temp);
        assertTrue(store.save(ApplicationSettings.defaults().withScraper(
                ScraperSettings.defaults().withTmdbApiKey(Optional.of("tmdb-key")))));

        assertEquals(Optional.of("tmdb-key"), store.load().scraper().tmdbApiKey());
    }

    @Test
    @DisplayName("the hosted Ollama endpoint counts as configured only with its key")
    void ollamaConfigurationDependsOnTheEndpoint() {
        assertFalse(ScraperSettings.defaults().ollamaConfigured());
        assertTrue(ScraperSettings.defaults()
                .withOllamaApiKey(Optional.of("ollama-key")).ollamaConfigured());
        // An Ollama in the house answers without any key at all.
        assertTrue(ScraperSettings.defaults()
                .withOllamaEndpoint("http://localhost:11434").ollamaConfigured());
    }

    @Test
    @DisplayName("blanked-out scraper fields fall back rather than sticking as empty")
    void scraperFieldsNormalise() {
        ScraperSettings scraper = new ScraperSettings(
                true, Optional.of("  spaced-key  "), Optional.empty(), " ", Optional.of("   "), "");

        assertEquals(Optional.of("spaced-key"), scraper.tvdbApiKey());
        assertEquals(ScraperSettings.DEFAULT_OLLAMA_ENDPOINT, scraper.ollamaEndpoint());
        assertEquals(Optional.empty(), scraper.ollamaApiKey());
        assertEquals(ScraperSettings.DEFAULT_OLLAMA_MODEL, scraper.ollamaModel());
    }

    @Test
    @DisplayName("the interval survives being written and read back")
    void roundTripsTheSlideshowInterval(@TempDir Path temp) {
        SettingsStore store = new SettingsStore(temp);
        assertTrue(store.save(ApplicationSettings.defaults().withSlideshowSeconds(9)));

        assertEquals(9, store.load().slideshowSeconds());
    }

    @Test
    @DisplayName("a configuration written before slideshows still loads")
    void defaultsTheIntervalWhenTheKeyIsAbsent(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("config.json"), "{\"fullScreen\": true, \"theme\": \"DARK\"}");

        assertEquals(5, new SettingsStore(temp).load().slideshowSeconds());
    }

    @Test
    void savingCreatesTheDataDirectory(@TempDir Path temp) {
        Path dataDirectory = temp.resolve("nested").resolve("SimpleMediaCenter");
        SettingsStore store = new SettingsStore(dataDirectory);

        assertTrue(store.save(ApplicationSettings.defaults()));

        assertTrue(Files.isRegularFile(dataDirectory.resolve("config.json")));
    }

    @Test
    @DisplayName("the specification's example configuration is understood as written")
    void readsTheSpecificationExample(@TempDir Path temp) throws IOException, JsonException {
        Files.writeString(temp.resolve("config.json"), """
                {
                  "vlcPath": "C:\\\\Program Files\\\\VideoLAN\\\\VLC\\\\vlc.exe",
                  "mediaRoots": [
                    {
                      "name": "Movies",
                      "path": "\\\\\\\\synology\\\\video\\\\Movies",
                      "type": "MOVIES"
                    }
                  ]
                }
                """);

        ApplicationSettings settings = new SettingsStore(temp).load();

        assertEquals(Optional.of(Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe")), settings.vlcPath());
        assertEquals(1, settings.mediaRoots().size());
        MediaRoot root = settings.mediaRoots().getFirst();
        assertEquals("Movies", root.displayName());
        assertEquals(MediaRootType.MOVIES, root.type());
        assertFalse(root.id().isBlank(), "an id is generated when the file does not carry one");
    }

    @Test
    void unknownRootTypesFallBackToGeneral(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("config.json"),
                "{\"mediaRoots\":[{\"name\":\"Stuff\",\"path\":\"/media\",\"type\":\"PODCASTS\"}]}");

        ApplicationSettings settings = new SettingsStore(temp).load();

        assertEquals(MediaRootType.GENERAL, settings.mediaRoots().getFirst().type());
    }

    @Test
    void rootsWithoutAPathAreIgnored(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("config.json"),
                "{\"mediaRoots\":[{\"name\":\"Broken\"},{\"name\":\"Good\",\"path\":\"/media\"}]}");

        ApplicationSettings settings = new SettingsStore(temp).load();

        assertEquals(1, settings.mediaRoots().size());
        assertEquals("Good", settings.mediaRoots().getFirst().displayName());
    }

    @Test
    @DisplayName("a corrupt file is quarantined and the application still starts")
    void corruptConfigurationFallsBackToDefaults(@TempDir Path temp) throws IOException {
        Path file = temp.resolve("config.json");
        Files.writeString(file, "{ this is not json");

        ApplicationSettings settings = new SettingsStore(temp).load();

        assertEquals(ApplicationSettings.defaults().mediaRoots(), settings.mediaRoots());
        assertFalse(Files.exists(file), "the broken file is moved aside");
        assertTrue(Files.isRegularFile(temp.resolve("config.json.corrupt")));
    }

    @Test
    void serializedFormUsesTheDocumentedFieldNames(@TempDir Path temp) throws JsonException {
        ApplicationSettings settings = ApplicationSettings.defaults()
                .withRoot(MediaRoot.create("Movies", Path.of("/media/Movies"), MediaRootType.MOVIES));

        String json = Json.write(SettingsStore.toJson(settings));

        assertTrue(json.contains("\"mediaRoots\""));
        assertTrue(json.contains("\"name\": \"Movies\""));
        assertTrue(json.contains("\"type\": \"MOVIES\""));
        assertEquals(settings.mediaRoots(), SettingsStore.fromJson(Json.parseObject(json)).mediaRoots());
    }
}
