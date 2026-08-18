package mediacenter.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.JsonException;
import mediacenter.json.JsonFiles;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/** Loads and saves {@code watched.json}. Failures degrade to nothing watched. */
public final class WatchedStore {

    private static final Logger LOG = Logger.getLogger(WatchedStore.class.getName());
    private static final String FILE_NAME = "watched.json";

    private final Path file;

    public WatchedStore(Path applicationDataDirectory) {
        this.file = applicationDataDirectory.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    public WatchedVideos load() {
        try {
            Optional<JsonObject> document = JsonFiles.readObject(file);
            if (document.isEmpty()) {
                return new WatchedVideos();
            }
            return WatchedVideos.of(pathsFromJson(document.get()));
        } catch (JsonException e) {
            LOG.log(Level.WARNING, e, () -> "Watched file " + file + " is not valid JSON");
            JsonFiles.quarantine(file)
                    .ifPresent(target -> LOG.log(Level.WARNING, () -> "Moved invalid watched file to " + target));
            return new WatchedVideos();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not read watched file " + file);
            return new WatchedVideos();
        }
    }

    public boolean save(WatchedVideos watched) {
        try {
            JsonFiles.write(file, toJson(watched.paths()));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not write watched file " + file);
            return false;
        }
    }

    // -- mapping ------------------------------------------------------------

    public static JsonObject toJson(List<Path> paths) {
        List<JsonValue> items = new ArrayList<>();
        for (Path path : paths) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("path", new JsonString(path.toString()));
            items.add(new JsonObject(members));
        }
        Map<String, JsonValue> document = new LinkedHashMap<>();
        document.put("watched", new JsonArray(items));
        return new JsonObject(document);
    }

    public static List<Path> pathsFromJson(JsonObject document) {
        List<Path> paths = new ArrayList<>();
        for (JsonObject item : document.objectArray("watched")) {
            item.nonBlankString("path").map(Path::of).ifPresent(paths::add);
        }
        return paths;
    }
}
