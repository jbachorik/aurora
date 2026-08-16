package mediacenter.ui.components;

import mediacenter.config.Theme;

/**
 * Colours for the generated tile shown when a title has no artwork.
 *
 * <p>Generated in code rather than in CSS because the hue comes from the title
 * itself, so the same film always gets the same card. The two themes need
 * opposite treatments: deep cards carrying light text on a dark page, tinted
 * pastels carrying dark text on a light one.
 */
public final class PlaceholderColors {

    private PlaceholderColors() {
    }

    /** A JavaFX inline style setting the gradient background for a title. */
    public static String backgroundFor(String title, Theme theme) {
        int hue = hueFor(title);
        int secondHue = (hue + 22) % 360;
        if (theme.isDark()) {
            return gradient(hue, 42, 40, secondHue, 55, 22);
        }
        return gradient(hue, 16, 97, secondHue, 32, 86);
    }

    /** Stable hue in [0, 360) derived from the title. */
    static int hueFor(String title) {
        return Math.floorMod(title == null ? 0 : title.hashCode(), 360);
    }

    private static String gradient(
            int fromHue, int fromSaturation, int fromBrightness,
            int toHue, int toSaturation, int toBrightness) {

        return "-fx-background-color: linear-gradient(to bottom right,"
                + " hsb(" + fromHue + ", " + fromSaturation + "%, " + fromBrightness + "%),"
                + " hsb(" + toHue + ", " + toSaturation + "%, " + toBrightness + "%));";
    }
}
