package mediacenter.scrape;

import java.util.List;
import java.util.Optional;

/**
 * One series or film a metadata provider offered for a search — as much of it
 * as the match needs, in the same shape whichever database answered. The id
 * means something only to the provider that issued it.
 */
public record TitleCandidate(
        long id,
        String name,
        List<String> aliases,
        Optional<Integer> year,
        Optional<String> overview,
        Optional<String> status,
        Optional<String> posterUrl) {

    public TitleCandidate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A title candidate must carry a name");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        year = year == null ? Optional.empty() : year;
        overview = overview == null ? Optional.empty() : overview;
        status = status == null ? Optional.empty() : status;
        posterUrl = posterUrl == null ? Optional.empty() : posterUrl;
    }
}
