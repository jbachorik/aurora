package mediacenter.history;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

/** Loads and saves {@code history.json}. Failures degrade to an empty history. */
public final class HistoryStore {

    private static final Logger LOG = Logger.getLogger(HistoryStore.class.getName());
    private static final String FILE_NAME = "history.json";

    private final Path file;

    public HistoryStore(Path applicationDataDirectory) {
        this.file = applicationDataDirectory.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    public PlaybackHistory load() {
        try {
            Optional<JsonObject> document = JsonFiles.readObject(file);
            if (document.isEmpty()) {
                return new PlaybackHistory();
            }
            return PlaybackHistory.of(entriesFromJson(document.get()));
        } catch (JsonException e) {
            LOG.log(Level.WARNING, e, () -> "History file " + file + " is not valid JSON");
            JsonFiles.quarantine(file)
                    .ifPresent(target -> LOG.log(Level.WARNING, () -> "Moved invalid history to " + target));
            return new PlaybackHistory();
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not read history file " + file);
            return new PlaybackHistory();
        }
    }

    public boolean save(PlaybackHistory history) {
        try {
            JsonFiles.write(file, toJson(history.entries()));
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, e, () -> "Could not write history file " + file);
            return false;
        }
    }

    // -- mapping ------------------------------------------------------------

    public static JsonObject toJson(List<PlaybackHistoryEntry> entries) {
        List<JsonValue> items = new ArrayList<>();
        for (PlaybackHistoryEntry entry : entries) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("path", new JsonString(entry.mediaPath().toString()));
            members.put("title", new JsonString(entry.displayTitle()));
            members.put("lastPlayed", new JsonString(entry.lastPlayed().toString()));
            items.add(new JsonObject(members));
        }
        Map<String, JsonValue> document = new LinkedHashMap<>();
        document.put("entries", new JsonArray(items));
        return new JsonObject(document);
    }

    public static List<PlaybackHistoryEntry> entriesFromJson(JsonObject document) {
        List<PlaybackHistoryEntry> entries = new ArrayList<>();
        for (JsonObject item : document.objectArray("entries")) {
            readEntry(item).ifPresent(entries::add);
        }
        return entries;
    }

    private static Optional<PlaybackHistoryEntry> readEntry(JsonObject document) {
        Optional<String> path = document.nonBlankString("path");
        if (path.isEmpty()) {
            return Optional.empty();
        }
        String title = document.nonBlankString("title").orElse(path.get());
        Instant lastPlayed = document.nonBlankString("lastPlayed")
                .flatMap(HistoryStore::parseInstant)
                .orElse(Instant.EPOCH);
        return Optional.of(new PlaybackHistoryEntry(Path.of(path.get()), title, lastPlayed));
    }

    private static Optional<Instant> parseInstant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
