package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.config.Theme;

class PlaceholderColorsTest {

    @Test
    @DisplayName("the same title always gets the same card")
    void coloursAreStablePerTitle() {
        assertEquals(
                PlaceholderColors.backgroundFor("Alien (1979)", Theme.DARK),
                PlaceholderColors.backgroundFor("Alien (1979)", Theme.DARK));
    }

    @Test
    void differentTitlesUsuallyGetDifferentCards() {
        assertNotEquals(
                PlaceholderColors.backgroundFor("Alien (1979)", Theme.DARK),
                PlaceholderColors.backgroundFor("Arrival (2016)", Theme.DARK));
    }

    @Test
    @DisplayName("dark cards are deep, light cards are pale")
    void eachThemeGetsItsOwnTreatment() {
        String dark = PlaceholderColors.backgroundFor("Dune (2021)", Theme.DARK);
        String light = PlaceholderColors.backgroundFor("Dune (2021)", Theme.LIGHT);

        assertNotEquals(dark, light);
        assertTrue(dark.contains("40%"), "the dark card starts at a low brightness: " + dark);
        assertTrue(light.contains("97%"), "the light card starts near white: " + light);
    }

    @Test
    void hueStaysInRangeForAnyTitle() {
        for (String title : new String[] {"", "a", "Ω", "Blade Runner 2049 (2017)", "🍿"}) {
            int hue = PlaceholderColors.hueFor(title);
            assertTrue(hue >= 0 && hue < 360, "hue out of range for '" + title + "': " + hue);
        }
    }

    @Test
    void bothStopsAreValidJavaFxStyles() {
        String style = PlaceholderColors.backgroundFor("Heat (1995)", Theme.LIGHT);

        assertTrue(style.startsWith("-fx-background-color: linear-gradient("), style);
        assertTrue(style.endsWith(");"), style);
        assertEquals(2, style.split("hsb\\(", -1).length - 1, "a gradient needs two stops");
    }
}
