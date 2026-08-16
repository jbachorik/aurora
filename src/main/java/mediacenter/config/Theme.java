package mediacenter.config;

import java.util.Locale;
import java.util.Optional;

/**
 * Colour scheme for the interface.
 *
 * <p>Dark is the default: a media center is normally used in a dim room, the
 * player it hands over to is fullscreen black, and posters read better against
 * a dark page. Light exists for bright rooms and for people who simply prefer
 * it — that judgement can only be made in front of the actual television.
 */
public enum Theme {

    DARK("Dark", "/mediacenter/ui/theme-dark.css"),
    LIGHT("Light", "/mediacenter/ui/theme-light.css");

    private final String displayName;
    private final String stylesheet;

    Theme(String displayName, String stylesheet) {
        this.displayName = displayName;
        this.stylesheet = stylesheet;
    }

    public String displayName() {
        return displayName;
    }

    /** Resource path of the palette stylesheet for this theme. */
    public String stylesheet() {
        return stylesheet;
    }

    /** True when the page is darker than the text on it. */
    public boolean isDark() {
        return this == DARK;
    }

    /** Lenient parsing so a hand-edited configuration file cannot break startup. */
    public static Optional<Theme> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (Theme theme : values()) {
            if (theme.name().equals(normalized)) {
                return Optional.of(theme);
            }
        }
        return Optional.empty();
    }
}
