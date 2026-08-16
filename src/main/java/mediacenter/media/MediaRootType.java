package mediacenter.media;

import java.util.Locale;
import java.util.Optional;

/** How a media root should be presented. */
public enum MediaRootType {

    /** Poster-oriented content: rendered as a movie-style tile grid. */
    MOVIES("Movies"),

    /** Series content. Presented like {@link #MOVIES} until series parsing exists. */
    TV("TV"),

    /** Anything else: plain folder browsing. */
    GENERAL("General");

    private final String displayName;

    MediaRootType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Lenient parsing so a hand-edited configuration file cannot break startup. */
    public static Optional<MediaRootType> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (MediaRootType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** True when this root should use the poster grid rather than plain folder tiles. */
    public boolean usesPosterLayout() {
        return this == MOVIES || this == TV;
    }
}
