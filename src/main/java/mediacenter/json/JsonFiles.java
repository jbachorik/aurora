package mediacenter.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import mediacenter.json.JsonValue.JsonObject;

/** Reading and writing small JSON documents without losing the previous file on a crash. */
public final class JsonFiles {

    private JsonFiles() {
    }

    /**
     * Reads a JSON object.
     *
     * @return empty when the file does not exist
     * @throws IOException   when the file exists but cannot be read
     * @throws JsonException when the content is not a valid JSON object
     */
    public static Optional<JsonObject> readObject(Path file) throws IOException, JsonException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Json.parseObject(text));
    }

    /** Writes via a temporary file so a partial write can never replace a good file. */
    public static void write(Path file, JsonValue value) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, Json.write(value), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Moves a file that could not be parsed out of the way so the application can
     * start with defaults without silently discarding what the user had.
     */
    public static Optional<Path> quarantine(Path file) {
        Path target = file.resolveSibling(file.getFileName() + ".corrupt");
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(target);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
