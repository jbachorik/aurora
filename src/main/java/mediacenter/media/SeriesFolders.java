package mediacenter.media;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Recognising a folder whose videos are episodes of one thing, meant to be
 * watched in order — where finishing one file should roll into the next.
 *
 * <p>Deliberately conservative, like {@link DisplayNames}: chaining two
 * unrelated films is worse than asking the viewer to press Enter again, so a
 * folder only counts when it holds at least two videos and <em>every one</em>
 * of them carries an ordering mark. Two marks are recognised:
 *
 * <ul>
 *   <li>an episode tag anywhere in the name — {@code S01E01}, {@code S01.E01}
 *       or {@code 1x01};
 *   <li>a leading "01 - " style ordering prefix, the same shape
 *       {@code DisplayNames} strips from display. One or two digits only, so a
 *       leading year — "2001 - A Space Odyssey" — never reads as ordering.
 * </ul>
 *
 * <p>A root configured as TV needs no marks at all: the viewer has already
 * said what the folders hold.
 */
public final class SeriesFolders {

    /**
     * {@code S01E01}, {@code s1e1}, {@code 1x01} — the season and episode may be
     * parted by a short run of spaces, dots, underscores or dashes — up to three
     * characters, so {@code S01.E01} (what a good many rippers write) and
     * {@code S01 - E01} (its spaced-out cousin, "-" with a space either side)
     * both read as one tag. The episode marker itself may be a bare {@code E} or
     * the {@code Ep} some rippers write instead — {@code S01Ep01}.
     *
     * <p>Shared with {@link DisplayNames}, which strips the same shape off the
     * end of what is shown — this string carries no word-boundary guard of its
     * own, so each user of it supplies the boundary its own matching needs.
     */
    static final String EPISODE_TAG_CORE = "s\\d{1,2}[\\s._-]{0,3}ep?\\d{1,3}|\\d{1,2}x\\d{1,3}";

    /**
     * Bounded by non-alphanumerics so the {@code 80x72} inside a
     * {@code 1280x720} resolution tag never counts, and an episode number has
     * to follow the separator, or "Series 1 - Episode 1" would start counting
     * as a tag. Reading either as two unrelated numbers left the folder
     * queueing nothing at all: the run stopped dead after its first episode,
     * with nothing on screen to say why.
     */
    private static final Pattern EPISODE_TAG = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:" + EPISODE_TAG_CORE + ")(?![\\p{L}\\p{N}])");

    /** @see DisplayNames the same shape its display-stripping recognises */
    private static final Pattern ORDERING_PREFIX = Pattern.compile("^\\d{1,2}\\s*[-.)\\]]\\s*");

    private SeriesFolders() {
    }

    /**
     * True when these video file names read as one ordered run of episodes.
     *
     * @param videoFileNames the folder's video names as they are on disk, in
     *                       the order the listing plays them
     */
    public static boolean looksLikeEpisodes(List<String> videoFileNames) {
        return videoFileNames.size() >= 2
                && videoFileNames.stream().allMatch(SeriesFolders::carriesAnOrderingMark);
    }

    static boolean carriesAnOrderingMark(String fileName) {
        return EPISODE_TAG.matcher(fileName).find()
                || ORDERING_PREFIX.matcher(fileName).find();
    }
}
