package mediacenter.scrape;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.media.VideoFiles;

/**
 * Turns loose films on a Movies shelf into the one-folder-per-film shape.
 *
 * <p>A shelf of bare files — {@code Movies/Heat.1995.mkv} — is the one layout
 * the movie scraper cannot serve, because the folder is where its answer
 * lives. So the shelf is tidied first: each qualifying file becomes
 * {@code Movies/Heat.1995/Heat.1995.mkv}, named exactly after the file — no
 * cleverness, the scraper does the reading — and its sidecars (subtitles,
 * artwork, whatever shares the file's base name) move in with it. A pure
 * rename on the same volume, so even a big shelf tidies in moments.
 *
 * <p>Moving someone's files asks for more caution than writing new ones, so
 * the disqualifications are broad and every doubt leaves a file where it is:
 *
 * <ul>
 *   <li>a file with an ordering prefix or an episode tag is part of a run —
 *       a boxed collection, a misfiled season — and folding it away would
 *       break the chain that plays it onwards;
 *   <li>a folder one of whose videos it is <em>named after</em> is already
 *       that film's folder — its extras and all — and is left untouched;
 *   <li>a ripper's {@code sample.mkv}, and any file whose would-be folder
 *       already exists, stay where they are.
 * </ul>
 *
 * <p>Blocking, and it renames things: runs where the scrapes run.
 */
public final class LooseMovieOrganizer {

    private static final Logger LOG = Logger.getLogger(LooseMovieOrganizer.class.getName());

    /**
     * Similarity at which a video is read as being what its folder is about.
     * The same reading {@link SeriesMatcher} applies to titles — and erring
     * on the high side here is safe, because the error is a shelf left alone.
     */
    private static final double FOLDER_CLAIM_SIMILARITY = 0.6;

    /** One relocated film: where the video was, and where it now is. */
    public record Move(Path from, Path to) {
    }

    /**
     * Tidies one folder, returning the film moves actually made — the videos
     * only; sidecars travel along but nothing downstream tracks them.
     *
     * @param folderIsMediaRoot a configured root's name describes a library,
     *                          so it can never be a film's own folder and its
     *                          loose files are always candidates
     */
    public List<Move> organize(Path folder, boolean folderIsMediaRoot) {
        List<String> videoNames = new ArrayList<>();
        List<String> otherFileNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                Path entryName = entry.getFileName();
                if (entryName == null || VideoFiles.isJunk(entryName.toString())
                        || !Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entryName.toString();
                if (VideoFiles.isVideoFileName(name)) {
                    videoNames.add(name);
                } else {
                    otherFileNames.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        if (videoNames.isEmpty()
                || (!folderIsMediaRoot && folderClaimedByAVideo(folder, videoNames))) {
            return List.of();
        }

        List<Move> moves = new ArrayList<>();
        for (String videoName : videoNames) {
            if (MovieEvidenceCollector.isSample(videoName)
                    || EpisodeTags.parse(videoName).isPresent()
                    || EpisodeTags.parseOrderingPrefix(videoName).isPresent()) {
                continue;
            }
            folderize(folder, videoName, otherFileNames).ifPresent(moves::add);
        }
        return List.copyOf(moves);
    }

    /**
     * Whether one of the videos is what the folder is named for — in which
     * case this is a film's own folder, extras beside it and all, not a shelf.
     */
    private static boolean folderClaimedByAVideo(Path folder, List<String> videoNames) {
        Path folderName = folder.getFileName();
        if (folderName == null) {
            return false;
        }
        return videoNames.stream()
                .filter(name -> !MovieEvidenceCollector.isSample(name))
                .anyMatch(name -> SeriesMatcher.similarity(
                        VideoFiles.withoutExtension(name), folderName.toString())
                        >= FOLDER_CLAIM_SIMILARITY);
    }

    /** Moves one film and its sidecars into a folder named after the file. */
    private static Optional<Move> folderize(
            Path folder, String videoName, List<String> otherFileNames) {
        String baseName = VideoFiles.withoutExtension(videoName);
        if (baseName.isBlank()) {
            return Optional.empty();
        }
        Path target = folder.resolve(baseName);
        if (Files.exists(target)) {
            // Whatever is there — a folder from an earlier half-finished tidy,
            // an unrelated file — is not this pass's to disturb.
            return Optional.empty();
        }
        Path from = folder.resolve(videoName);
        Path to = target.resolve(videoName);
        try {
            Files.createDirectory(target);
            Files.move(from, to);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, e, () -> "Could not move " + from + " into its own folder");
            try {
                // The folder may have been created before the move failed; an
                // empty leftover would block the retry on the next start.
                Files.deleteIfExists(target);
            } catch (IOException | RuntimeException cleanup) {
                LOG.log(Level.FINE, "Could not remove the leftover folder " + target, cleanup);
            }
            return Optional.empty();
        }
        moveSidecars(folder, target, baseName, otherFileNames);
        LOG.log(Level.INFO, () -> "Moved " + videoName + " into its own folder");
        return Optional.of(new Move(from, to));
    }

    /**
     * Everything that shares the film's base name — {@code .srt}, {@code .jpg},
     * {@code .nfo} — follows it, or subtitles and artwork would silently stop
     * being found. A sidecar that will not move is logged and left; the film
     * itself is already home.
     */
    private static void moveSidecars(Path folder, Path target, String baseName, List<String> otherFileNames) {
        String prefix = baseName.toLowerCase(Locale.ROOT) + ".";
        for (String name : otherFileNames) {
            if (!name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            try {
                Files.move(folder.resolve(name), target.resolve(name));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.INFO, e, () -> "Could not move the sidecar " + name);
            }
        }
    }
}
