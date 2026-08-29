package mediacenter.scrape;

import java.util.Optional;

/** What the language model believes the series is called, and — when it knows — from which year. */
public record TitleGuess(String title, Optional<Integer> year) {

    public TitleGuess {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A title guess must carry a title");
        }
        title = title.trim();
        year = year == null ? Optional.empty() : year;
    }
}
