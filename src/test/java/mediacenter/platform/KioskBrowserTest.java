package mediacenter.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KioskBrowserTest {

    private static final Path PROFILE = Path.of("/data/browser-profile");
    private static final Path EXTENSION = Path.of("/data/browser-extension");

    // Programs are named as paths and asserted through Path.toString(): Windows
    // prints these same paths back with backslashes, and a literal here would
    // pass on a POSIX machine and fail on the runner that matters most.
    private static final Path CHROME = Path.of("/usr/bin/google-chrome");
    private static final Path CHROMIUM = Path.of("/usr/bin/chromium");
    private static final Path FIREFOX = Path.of("/usr/bin/firefox");
    private static final Path EPIPHANY = Path.of("/usr/bin/epiphany");

    @Test
    @DisplayName("a Chromium gets app mode, its own profile, fullscreen and the scale hint")
    void buildsTheChromiumCommand() {
        List<String> command = KioskBrowser.commandFor(
                CHROMIUM, "https://cinema.mosfilm.ru", 150, PROFILE, Optional.of(EXTENSION));

        assertEquals(List.of(
                CHROMIUM.toString(),
                "--user-data-dir=" + PROFILE,
                "--no-first-run",
                "--no-default-browser-check",
                "--load-extension=" + EXTENSION,
                "--force-device-scale-factor=1.50",
                "--start-fullscreen",
                "--app=https://cinema.mosfilm.ru"), command);
    }

    @Test
    @DisplayName("branded Chrome is maximized, not fullscreened: without the extension's F key, "
            + "an already-fullscreen window would leave embedded players' own buttons dead")
    void brandedChromeIsMaximizedNotFullscreened() {
        List<String> command = KioskBrowser.commandFor(
                CHROME, "https://cinema.mosfilm.ru", 150, PROFILE, Optional.of(EXTENSION));

        assertTrue(command.contains("--start-maximized"), command.toString());
        assertFalse(command.contains("--start-fullscreen"), command.toString());
    }

    @Test
    @DisplayName("a scale of 100 says nothing and leaves the browser to its own judgement")
    void omitsTheScaleHintAtOneHundred() {
        List<String> command = KioskBrowser.commandFor(
                Path.of("/usr/bin/chromium"), "https://example.org", 100, PROFILE, Optional.empty());

        assertTrue(command.stream().noneMatch(argument -> argument.contains("scale-factor")),
                command.toString());
    }

    @Test
    @DisplayName("the scale is rendered with a dot whatever the machine's locale says")
    void formatsTheScaleLocaleIndependently() {
        List<String> command = KioskBrowser.commandFor(
                Path.of("chrome.exe"), "https://example.org", 225, PROFILE, Optional.empty());

        assertTrue(command.contains("--force-device-scale-factor=2.25"), command.toString());
    }

    @Test
    @DisplayName("Firefox knows kiosk mode and nothing else offered here")
    void firefoxGetsKioskMode() {
        assertEquals(
                List.of(FIREFOX.toString(), "--kiosk", "https://example.org"),
                KioskBrowser.commandFor(FIREFOX, "https://example.org", 150, PROFILE, Optional.of(EXTENSION)));
    }

    @Test
    @DisplayName("an unknown browser gets the address alone")
    void unknownBrowsersGetTheAddressAlone() {
        assertEquals(
                List.of(EPIPHANY.toString(), "https://example.org"),
                KioskBrowser.commandFor(EPIPHANY, "https://example.org", 150, PROFILE, Optional.of(EXTENSION)));
    }

    @Test
    @DisplayName("the family is recognised by name, capitals and all")
    void recognisesTheChromiumFamily() {
        assertTrue(KioskBrowser.isChromiumFamily("Chrome.exe"));
        assertTrue(KioskBrowser.isChromiumFamily("msedge.exe"));
        assertTrue(KioskBrowser.isChromiumFamily("chromium-browser"));
        assertTrue(KioskBrowser.isChromiumFamily("Brave Browser"));
        assertFalse(KioskBrowser.isChromiumFamily("firefox"));
        assertFalse(KioskBrowser.isChromiumFamily("safari"));
    }

    @Test
    @DisplayName("branded Chrome is told apart from Chromium, which honours --load-extension")
    void tellsBrandedChromeFromChromium() {
        assertTrue(KioskBrowser.isBrandedChrome("google-chrome"));
        assertTrue(KioskBrowser.isBrandedChrome("google-chrome-stable"));
        assertTrue(KioskBrowser.isBrandedChrome("Chrome.exe"));
        assertFalse(KioskBrowser.isBrandedChrome("chromium"));
        assertFalse(KioskBrowser.isBrandedChrome("chromium-browser"));
        assertFalse(KioskBrowser.isBrandedChrome("msedge.exe"));
        assertFalse(KioskBrowser.isBrandedChrome("Brave Browser"));
    }
}
