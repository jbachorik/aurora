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
 * Reads a folder into one {@link MovieEvidence} per film it holds, deciding on
 * the way whether it holds any films at all.
 *
 * <p>Usually that is one film in its own folder — "Blade Runner 2049 (2017)"
 * holding the video and perhaps its artwork, subtitles and extras folders —
 * but a trilogy nobody split apart shares its folder between several videos,
 * and each of those is its own film to identify, on the strength of its own
 * file name rather than the folder's. What disqualifies the whole folder is
 * being something else entirely: season folders (a series, wherever the
 * shelf put it), or no video at all (a folder that merely groups other
 * folders — whose own folders will be offered when they come on screen). An
 * episode tag disqualifies only the one video that carries it — a season
 * misfiled on a Movies shelf beside otherwise ordinary films.
 *
 * <p>A ripper's {@code sample.mkv} beside a film is ignored rather than
 * counted as a film of its own.
 */
public final class MovieEvidenceCollector {

    /** A parenthesised or bracketed year is a deliberate label; it wins outright. */
    private static final Pattern LABELLED_YEAR = Pattern.compile("[(\\[]((?:19|20)\\d{2})[)\\]]");

    /** A year standing alone between separators — the dotted-name convention. */
    private static final Pattern BARE_YEAR = Pattern.compile("(?:^|[\\s._-])((?:19|20)\\d{2})(?=[\\s._-]|$)");

    private final MediaDurationProbe durationProbe;

    public MovieEvidenceCollector(MediaDurationProbe durationProbe) {
        this.durationProbe = durationProbe;
    }

    /**
     * The folder's evidence, one entry per film — usually one, several for a
     * trilogy sharing its folder — or empty when it holds no film at all, or
     * simply cannot be listed, which over a share is an ordinary day.
     */
    public List<MovieEvidence> collect(Path movieFolder) {
        Path folderName = movieFolder.getFileName();
        if (folderName == null) {
            return List.of();
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
                        return List.of();
                    }
                    continue;
                }
                if (VideoFiles.isVideoFileName(name) && !isSample(name)) {
                    videoNames.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }

        // A video carrying an episode tag is a season misfiled on this shelf,
        // not a film beside the others — it drops out on its own rather than
        // disqualifying films that sit next to it in the same folder. Sorted,
        // since a directory listing's own order is nobody's to rely on, and a
        // trilogy's numbering ("01 - …", "02 - …") reads the same way either
        // way.
        List<String> filmVideoNames = videoNames.stream()
                .filter(name -> EpisodeTags.parse(name).isEmpty())
                .sorted()
                .toList();
        if (filmVideoNames.isEmpty()) {
            // Zero is a grouping folder, or a flat season with nothing but
            // tagged episodes — neither holds a film to identify.
            return List.of();
        }
        // The folder's own year, when it is unambiguous, is a candidate hint
        // for every film in it; each video's own name is asked too, since a
        // shared folder's name — a trilogy's box title, say — rarely carries
        // one film's specific year.
        Optional<Integer> folderYear = yearHintOf(folderName.toString());
        List<MovieEvidence> evidence = new ArrayList<>();
        for (String videoName : filmVideoNames) {
            Optional<Integer> year = folderYear.or(() -> yearHintOf(videoName));
            // Asked last, once a video has qualified as a film: the probe
            // opens the file, and a name that never qualified deserves no I/O.
            evidence.add(new MovieEvidence(
                    folderName.toString(),
                    videoName,
                    year,
                    durationProbe.durationOf(movieFolder.resolve(videoName))));
        }
        return List.copyOf(evidence);
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
    static boolean isSample(String videoFileName) {
        String base = VideoFiles.withoutExtension(videoFileName).toLowerCase(Locale.ROOT);
        return base.equals("sample") || base.startsWith("sample-") || base.startsWith("sample.")
                || base.endsWith("-sample") || base.endsWith(".sample");
    }
}
