package mediacenter.media;

import java.nio.file.Path;
import java.util.Optional;

/**
 * One entry in a browsed directory.
 *
 * <p>Kept deliberately small: no genres, cast, codecs or ratings.
 */
public record MediaItem(
        Path path,
        String displayName,
        MediaItemType type,
        Optional<Path> artworkPath,
        long lastModified) {

    public MediaItem {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Type must not be null");
        }
        artworkPath = artworkPath == null ? Optional.empty() : artworkPath;
    }

    public static MediaItem directory(Path path, String displayName, Optional<Path> artwork, long lastModified) {
        return new MediaItem(path, displayName, MediaItemType.DIRECTORY, artwork, lastModified);
    }

    public static MediaItem video(Path path, String displayName, Optional<Path> artwork, long lastModified) {
        return new MediaItem(path, displayName, MediaItemType.VIDEO, artwork, lastModified);
    }

    public boolean isDirectory() {
        return type == MediaItemType.DIRECTORY;
    }

    public boolean isVideo() {
        return type == MediaItemType.VIDEO;
    }
}
