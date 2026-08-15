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
    @DisplayName("settings survive a save/load round trip, UNC paths included")
    void roundTripsSettings(@TempDir Path temp) {
        SettingsStore store = new SettingsStore(temp);
        MediaRoot movies = MediaRoot.create(
                "Movies", Path.of("\\\\synology\\video\\Movies"), MediaRootType.MOVIES);
        MediaRoot tv = MediaRoot.create("TV", Path.of("D:\\Media\\TV"), MediaRootType.TV);
        ApplicationSettings original = new ApplicationSettings(
                Optional.of(Path.of("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe")),
                Optional.of(Path.of("C:\\Program Files\\Mozilla Firefox\\firefox.exe")),
                false,
                List.of(movies, tv));

        assertTrue(store.save(original));
        ApplicationSettings reloaded = store.load();

        assertEquals(original.vlcPath(), reloaded.vlcPath());
        assertEquals(original.browserPath(), reloaded.browserPath());
        assertFalse(reloaded.fullScreen());
        assertEquals(2, reloaded.mediaRoots().size());
        assertEquals(movies, reloaded.mediaRoots().getFirst());
        assertEquals("\\\\synology\\video\\Movies", reloaded.mediaRoots().getFirst().displayPath());
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
