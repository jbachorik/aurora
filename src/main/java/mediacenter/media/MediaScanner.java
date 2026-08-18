package mediacenter.media;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads a single directory into a list of browsable items.
 *
 * <p>Every method here blocks and is expected to be called off the JavaFX
 * application thread — a disconnected NAS must never freeze the UI.
 */
public final class MediaScanner {

    private static final Logger LOG = Logger.getLogger(MediaScanner.class.getName());

    /** Upper bound on parallel artwork lookups so a NAS is not flooded with requests. */
    private static final int ARTWORK_LOOKUP_PARALLELISM = 16;

    private final ArtworkResolver artworkResolver;

    public MediaScanner() {
        this(new ArtworkResolver());
    }

    public MediaScanner(ArtworkResolver artworkResolver) {
        this.artworkResolver = artworkResolver;
    }

    /**
     * Lists a directory that sits inside a media root.
     *
     * @see #scan(Path, boolean)
     */
    public List<MediaItem> scan(Path directory) throws MediaAccessException {
        return scan(directory, false);
    }

    /**
     * Lists a directory: sub-directories first, then video files, each sorted
     * case-insensitively by display name.
     *
     * @param directoryIsMediaRoot when true the folder is a configured root, whose
     *                             name describes a library rather than a title, so
     *                             it is never borrowed for a file's display name
     * @throws MediaAccessException when the directory cannot be read
     */
    public List<MediaItem> scan(Path directory, boolean directoryIsMediaRoot) throws MediaAccessException {
        return scan(directory, directoryIsMediaRoot, true);
    }

    /**
     * Lists a directory without looking up artwork for the sub-directories in it.
     *
     * <p>Each of those lookups is a directory listing of its own, and over a share
     * they are what the viewer waits through: a folder of twenty series is twenty
     * more round trips before the first tile can be drawn. Callers that can fill
     * artwork in afterwards use this and then {@link #directoryArtwork(Path)} per
     * folder, so the names appear at once and the posters follow.
     */
    public List<MediaItem> scanWithoutDirectoryArtwork(Path directory, boolean directoryIsMediaRoot)
            throws MediaAccessException {
        return scan(directory, directoryIsMediaRoot, false);
    }

    /**
     * Artwork for one folder, looked up on its own.
     *
     * <p>Blocking, and on a share slow enough to be felt: call it off the UI
     * thread, one folder at a time, and show each answer as it arrives.
     */
    public Optional<Path> directoryArtwork(Path directory) {
        return artworkResolver.resolveForDirectory(directory);
    }

    /**
     * The folder's one and only medium — exactly one video or photograph and no
     * sub-folders — or empty for any other folder.
     *
     * <p>This is the folder a browse list may collapse: activating it can go
     * straight to the file, because drilling in would only present a single
     * line to press Enter on again. Only what the scan would show counts —
     * artwork, sidecars and junk never keep a folder from being "just the
     * film", exactly as they never appear as lines of their own.
     *
     * <p>Blocking like every listing here: call it off the UI thread.
     *
     * @throws MediaAccessException when the directory cannot be read
     */
    public Optional<MediaItem> soleMedia(Path directory) throws MediaAccessException {
        List<MediaItem> items = scanWithoutDirectoryArtwork(directory, false);
        if (items.size() == 1 && !items.getFirst().isDirectory()) {
            return Optional.of(items.getFirst());
        }
        return Optional.empty();
    }

    private List<MediaItem> scan(Path directory, boolean directoryIsMediaRoot, boolean withDirectoryArtwork)
            throws MediaAccessException {
        verifyAccessible(directory);

        List<Path> directories = new ArrayList<>();
        List<Path> videos = new ArrayList<>();
        List<Path> photos = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        List<Long> videoTimestamps = new ArrayList<>();
        List<Long> photoTimestamps = new ArrayList<>();
        List<Long> directoryTimestamps = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                Path fileNamePath = entry.getFileName();
                if (fileNamePath == null) {
                    continue;
                }
                String fileName = fileNamePath.toString();
                if (VideoFiles.isJunk(fileName)) {
                    continue;
                }
                Optional<BasicFileAttributes> attributes = readAttributes(entry);
                if (attributes.isEmpty() || isHiddenOrSystem(attributes.get())) {
                    continue;
                }
                BasicFileAttributes basic = attributes.get();
                if (basic.isDirectory()) {
                    directories.add(entry);
                    directoryTimestamps.add(basic.lastModifiedTime().toMillis());
                } else if (basic.isRegularFile()) {
                    fileNames.add(fileName);
                    if (VideoFiles.isVideoFileName(fileName)) {
                        videos.add(entry);
                        videoTimestamps.add(basic.lastModifiedTime().toMillis());
                    } else if (PhotoFiles.isPhotoFileName(fileName)) {
                        photos.add(entry);
                        photoTimestamps.add(basic.lastModifiedTime().toMillis());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw accessFailure(directory, e);
        }

        Set<String> artwork = ArtworkResolver.artworkNames(fileNames);
        List<MediaItem> photoItems = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path photo = photos.get(i);
            if (artwork.contains(photo.getFileName().toString())) {
                // A film's poster or sidecar: the grid shows it as that film's
                // artwork, and the slideshow skips it for the same reason.
                continue;
            }
            photoItems.add(MediaItem.image(photo, DisplayNames.forFile(photo),
                    Optional.of(photo), photoTimestamps.get(i)));
        }

        List<MediaItem> items = new ArrayList<>();
        items.addAll(directoryItems(directories, directoryTimestamps, withDirectoryArtwork));

        // videoItems sorts its own list, so the two are combined and sorted again.
        List<MediaItem> files = new ArrayList<>(videoItems(
                videos, videoTimestamps, fileNames, directory,
                directories.isEmpty() && !directoryIsMediaRoot));
        files.addAll(photoItems);
        files.sort(BY_FILE_NAME);
        items.addAll(files);
        return List.copyOf(items);
    }

    /**
     * Checks that a path can be used as a media location.
     *
     * @throws MediaAccessException with a message suitable for display
     */
    public void verifyAccessible(Path directory) throws MediaAccessException {
        try {
            if (!Files.exists(directory)) {
                throw new MediaAccessException(directory, cannotAccessMessage(directory), null);
            }
            if (!Files.isDirectory(directory)) {
                throw new MediaAccessException(directory, "This location is not a folder.", null);
            }
            if (!Files.isReadable(directory)) {
                throw new MediaAccessException(directory, "Permission denied. Windows could not open this folder.", null);
            }
        } catch (SecurityException e) {
            throw new MediaAccessException(directory, "Permission denied. Windows could not open this folder.", e);
        }
    }

    /**
     * Order comes from the name on disk, not from the displayed one. An "01 - "
     * prefix is there to order a series and is deliberately not shown, so sorting
     * on the display name would throw away the very thing it was added for.
     */
    private static final Comparator<MediaItem> BY_FILE_NAME =
            Comparator.comparing(MediaScanner::fileNameOf, String.CASE_INSENSITIVE_ORDER);

    private static String fileNameOf(MediaItem item) {
        Path name = item.path().getFileName();
        return name == null ? item.displayName() : name.toString();
    }

    // -- item construction --------------------------------------------------

    private List<MediaItem> directoryItems(
            List<Path> directories, List<Long> timestamps, boolean withArtwork) {
        List<Optional<Path>> artwork = withArtwork
                ? resolveDirectoryArtwork(directories)
                : Collections.nCopies(directories.size(), Optional.<Path>empty());
        List<MediaItem> items = new ArrayList<>(directories.size());
        for (int i = 0; i < directories.size(); i++) {
            Path directory = directories.get(i);
            items.add(MediaItem.directory(
                    directory,
                    DisplayNames.forDirectory(directory),
                    artwork.get(i),
                    timestamps.get(i)));
        }
        items.sort(BY_FILE_NAME);
        return items;
    }

    private List<MediaItem> videoItems(
            List<Path> videos,
            List<Long> timestamps,
            List<String> siblingFileNames,
            Path directory,
            boolean directoryNameMayBeBorrowed) {

        // A folder holding a single movie file is normally named after the movie
        // ("Blade Runner 2049 (2017)/Blade.Runner.2049.2017.mkv"), and that name
        // reads much better than the file name.
        boolean preferDirectoryName = directoryNameMayBeBorrowed
                && videos.size() == 1
                && directory.getFileName() != null;

        List<MediaItem> items = new ArrayList<>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            Path video = videos.get(i);
            String displayName = preferDirectoryName
                    ? DisplayNames.forDirectory(directory)
                    : DisplayNames.forFile(video);
            items.add(MediaItem.video(
                    video,
                    displayName,
                    artworkResolver.resolveForFile(video, siblingFileNames),
                    timestamps.get(i)));
        }
        items.sort(BY_FILE_NAME);
        return items;
    }

    /** Looks up per-directory artwork concurrently; each lookup is a blocking listing. */
    private List<Optional<Path>> resolveDirectoryArtwork(List<Path> directories) {
        if (directories.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(ARTWORK_LOOKUP_PARALLELISM);
        List<Optional<Path>> results = new ArrayList<>(directories.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<Path>>> futures = new ArrayList<>(directories.size());
            for (Path directory : directories) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return artworkResolver.resolveForDirectory(directory);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<Optional<Path>> future : futures) {
                results.add(artworkOf(future));
            }
        }
        return results;
    }

    private static Optional<Path> artworkOf(Future<Optional<Path>> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Artwork lookup failed", e);
            return Optional.empty();
        }
    }

    // -- attributes ---------------------------------------------------------

    private static Optional<BasicFileAttributes> readAttributes(Path entry) {
        try {
            // On Windows this single call also yields the hidden/system flags.
            return Optional.of(Files.readAttributes(entry, DosFileAttributes.class));
        } catch (IOException | RuntimeException e) {
            // UnsupportedOperationException lands here on filesystems without the DOS view.
            try {
                return Optional.of(Files.readAttributes(entry, BasicFileAttributes.class));
            } catch (IOException | RuntimeException nested) {
                LOG.log(Level.FINE, "Skipping unreadable entry " + entry, nested);
                return Optional.empty();
            }
        }
    }

    // The same verdict as FileVisibility.isHiddenOrSystem, reached from
    // attributes this listing has already read.
    private static boolean isHiddenOrSystem(BasicFileAttributes attributes) {
        return attributes instanceof DosFileAttributes dos && (dos.isHidden() || dos.isSystem());
    }

    // -- error messages -----------------------------------------------------

    private static MediaAccessException accessFailure(Path directory, Exception cause) {
        String message = switch (cause) {
            case AccessDeniedException ignored -> "Permission denied. Windows could not open this folder.";
            case NoSuchFileException ignored -> "This folder no longer exists.";
            case NotDirectoryException ignored -> "This location is not a folder.";
            default -> cannotAccessMessage(directory);
        };
        return new MediaAccessException(directory, message, cause);
    }

    /** The specification's wording for an unreachable share. */
    static String cannotAccessMessage(Path directory) {
        if (isNetworkPath(directory)) {
            return "Cannot access " + directory + ". Check network connectivity or Windows SMB credentials.";
        }
        return "Cannot access " + directory + ". The folder may have been moved, renamed or disconnected.";
    }

    static boolean isNetworkPath(Path path) {
        String text = path.toString();
        return text.startsWith("\\\\") || text.startsWith("//");
    }
}
