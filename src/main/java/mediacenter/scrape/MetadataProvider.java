package mediacenter.scrape;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A metadata database the scraper can ask — TheTVDB or TMDB, behind one
 * shape, so the pipeline and the matchers never know which one answered.
 *
 * <p>Four questions, and the contract every answer shares: failures come back
 * empty, never as exceptions. A scrape is a background nicety, and the folder
 * it was about plays exactly as well without it.
 */
public interface MetadataProvider {

    /** The provider's name as shown in logs and stored in the metadata file. */
    String name();

    /** Series matching a title, best first as the provider ranks them. */
    List<TitleCandidate> searchSeries(String query);

    /** Films matching a title, best first as the provider ranks them. */
    List<TitleCandidate> searchMovies(String query);

    /**
     * How many aired episodes each season of a series holds. Empty when the
     * answer could not be fetched — which the matcher treats as "no opinion",
     * never as a mismatch.
     */
    Optional<Map<Integer, Integer>> episodesPerSeason(long seriesId);

    /** A film's official running time in minutes; empty is "no opinion" too. */
    Optional<Integer> movieRuntimeMinutes(long movieId);
}
