package mediacenter.scrape;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mediacenter.media.VideoFiles;

/**
 * Reads a folder into {@link MovieEvidence}, deciding on the way whether it is
 * a movie folder at all.
 *
 * <p>A movie folder is the one-folder-per-film shape the browser already
 * favours — "Blade Runner 2049 (2017)" holding the film and perhaps its
 * artwork, subtitles and extras folders. What disqualifies a folder is being
 * something else: more than one feature-length claim (a collection), an
 * episode tag on the video (a season misfiled on a Movies shelf), season
 * folders, or no video at all (a folder that merely groups other folders —
 * whose own folders will be offered when they come on screen).
 *
 * <p>A ripper's {@code sample.mkv} beside the film is ignored rather than
 * counted, because otherwise half the shelf would read as "two videos" and
 * never be identified.
 */
public final class MovieEvidenceCollector {

    /** A parenthesised or bracketed year is a deliberate label; it wins outright. */
    private static final Pattern LABELLED_YEAR = Pattern.compile("[(\\[]((?:19|20)\\d{2})[)\\]]");

    /** A year standing alone between separators — the dotted-name convention. */
    private static final Pattern BARE_YEAR = Pattern.compile("(?:^|[\\s._-])((?:19|20)\\d{2})(?=[\\s._-]|$)");

    /**
     * The folder's evidence, or empty when it does not read as one film —
     * or simply cannot be listed, which over a share is an ordinary day.
     */
    public Optional<MovieEvidence> collect(Path movieFolder) {
        Path folderName = movieFolder.getFileName();
        if (folderName == null) {
            return Optional.empty();
        }

        List<String> videoNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(movieFolder)) {
            for (Path entry : stream) {
                Path entryName = entry.getFileName();
                if (entryName == null || VideoFiles.isJunk(entryName.toString())) {
                    continue;
                }
                String name = entryName.toString();
                if (Files.isDirectory(entry)) {
                    if (SeriesEvidenceCollector.seasonNumberOf(name).isPresent()) {
                        // Season folders mean a series, wherever the shelf put it.
                        return Optional.empty();
                    }
                    continue;
                }
                if (VideoFiles.isVideoFileName(name) && !isSample(name)) {
                    videoNames.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }

        if (videoNames.size() != 1) {
            // Zero is a grouping folder; several is a collection or a flat
            // season — neither is one film to identify.
            return Optional.empty();
        }
        String videoName = videoNames.getFirst();
        if (EpisodeTags.parse(videoName).isPresent()) {
            return Optional.empty();
        }
        Optional<Integer> year = yearHintOf(folderName.toString())
                .or(() -> yearHintOf(videoName));
        return Optional.of(new MovieEvidence(folderName.toString(), videoName, year));
    }

    /**
     * The release year a name carries, when it clearly carries one.
     *
     * <p>"(2017)" and "[2017]" are labels and always count. A bare year is
     * trusted only away from the front — "Blade.Runner.2049.2017" ends with
     * its year, while "2001 - A Space Odyssey" and "2012" <em>start</em> with
     * a title — and only when it could be a release year at all, which is how
     * the 2049 in the middle stays part of the title.
     */
    static Optional<Integer> yearHintOf(String name) {
        Matcher labelled = LABELLED_YEAR.matcher(name);
        if (labelled.find()) {
            return Optional.of(Integer.parseInt(labelled.group(1)))
                    .filter(MovieEvidenceCollector::isPlausibleReleaseYear);
        }
        Matcher bare = BARE_YEAR.matcher(name);
        Integer last = null;
        while (bare.find()) {
            if (bare.start(1) == 0) {
                continue;
            }
            int year = Integer.parseInt(bare.group(1));
            if (isPlausibleReleaseYear(year)) {
                last = year;
            }
        }
        return Optional.ofNullable(last);
    }

    /** Cinema has a first year, and next year's releases already leak online. */
    private static boolean isPlausibleReleaseYear(int year) {
        return year >= 1930 && year <= Year.now().getValue() + 1;
    }

    /** The preview file a ripper leaves beside the film. */
    private static boolean isSample(String videoFileName) {
        String base = VideoFiles.withoutExtension(videoFileName).toLowerCase(Locale.ROOT);
        return base.equals("sample") || base.startsWith("sample-") || base.startsWith("sample.")
                || base.endsWith("-sample") || base.endsWith(".sample");
    }
}
