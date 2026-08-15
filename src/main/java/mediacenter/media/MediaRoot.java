package mediacenter.media;

import java.nio.file.Path;
import java.util.UUID;

/**
 * A configured top-level media location: a local directory or a UNC/SMB share.
 *
 * <p>The application never authenticates against SMB itself — the operating
 * system owns share credentials and connectivity.
 */
public record MediaRoot(String id, String displayName, Path path, MediaRootType type) {

    public MediaRoot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Media root id must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Media root display name must not be blank");
        }
        if (path == null) {
            throw new IllegalArgumentException("Media root path must not be null");
        }
        if (type == null) {
            throw new IllegalArgumentException("Media root type must not be null");
        }
    }

    /** Creates a root with a freshly generated identifier. */
    public static MediaRoot create(String displayName, Path path, MediaRootType type) {
        return new MediaRoot(UUID.randomUUID().toString(), displayName, path, type);
    }

    public MediaRoot withDisplayName(String newDisplayName) {
        return new MediaRoot(id, newDisplayName, path, type);
    }

    public MediaRoot withPath(Path newPath) {
        return new MediaRoot(id, displayName, newPath, type);
    }

    public MediaRoot withType(MediaRootType newType) {
        return new MediaRoot(id, displayName, path, newType);
    }

    /** The path as the user configured it, suitable for display and for logs. */
    public String displayPath() {
        return path.toString();
    }
}
