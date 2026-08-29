package mediacenter.scrape;

import java.util.List;
import java.util.Optional;

/** One series TheTVDB offered for a search, as much of it as the match needs. */
public record TvdbCandidate(
        long tvdbId,
        String name,
        List<String> aliases,
        Optional<Integer> year,
        Optional<String> overview,
        Optional<String> status,
        Optional<String> posterUrl) {

    public TvdbCandidate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A series candidate must carry a name");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        year = year == null ? Optional.empty() : year;
        overview = overview == null ? Optional.empty() : overview;
        status = status == null ? Optional.empty() : status;
        posterUrl = posterUrl == null ? Optional.empty() : posterUrl;
    }
}
