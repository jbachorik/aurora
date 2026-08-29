package mediacenter.scrape;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the season and episode numbers out of a file name.
 *
 * <p>{@link mediacenter.media.SeriesFolders} answers the yes/no question — "is
 * this an ordered run?" — and deliberately keeps no numbers. The scraper needs
 * the numbers themselves: how many episodes each season holds is what lets a
 * guessed title be checked against what TheTVDB says the series looks like.
 * The shapes recognised here are exactly the ones {@code SeriesFolders}
 * recognises, so the two can never disagree about what counts as an episode.
 */
public final class EpisodeTags {

    /** A parsed tag: which season, which episode within it. */
    public record SeasonEpisode(int season, int episode) {
    }

    /**
     * The same shapes as {@code SeriesFolders#EPISODE_TAG}, with the numbers
     * captured: {@code S01E01}, {@code S01.E01}, {@code S01Ep01}, {@code 1x01}.
     */
    private static final Pattern EPISODE_TAG = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:s(\\d{1,2})[\\s._-]{0,3}ep?(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))(?![\\p{L}\\p{N}])");

    /** A leading "01 - " ordering prefix: an episode number with no season named. */
    private static final Pattern ORDERING_PREFIX = Pattern.compile("^(\\d{1,2})\\s*[-.)\\]]\\s*");

    private EpisodeTags() {
    }

    /** The season and episode a file name carries, when it carries a full tag. */
    public static Optional<SeasonEpisode> parse(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher matcher = EPISODE_TAG.matcher(fileName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        // Group 1/2 is the SxxEyy form, group 3/4 the 1x01 form; exactly one
        // of the two alternatives has matched.
        String season = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        String episode = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        return Optional.of(new SeasonEpisode(Integer.parseInt(season), Integer.parseInt(episode)));
    }

    /**
     * The episode number of a "01 - Title" style name — ordering with no season
     * of its own, which a flat folder of episodes reads as season one.
     */
    public static Optional<Integer> parseOrderingPrefix(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher matcher = ORDERING_PREFIX.matcher(fileName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1)));
    }
}
