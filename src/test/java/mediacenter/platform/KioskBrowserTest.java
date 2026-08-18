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

    @Test
    @DisplayName("a Chromium gets app mode, its own profile, fullscreen and the scale hint")
    void buildsTheChromiumCommand() {
        List<String> command = KioskBrowser.commandFor(
                Path.of("/usr/bin/google-chrome"), "https://cinema.mosfilm.ru", 150, PROFILE, Optional.of(EXTENSION));

        assertEquals(List.of(
                "/usr/bin/google-chrome",
                "--user-data-dir=" + PROFILE,
                "--no-first-run",
                "--no-default-browser-check",
                "--load-extension=" + EXTENSION,
                "--force-device-scale-factor=1.50",
                "--start-fullscreen",
                "--app=https://cinema.mosfilm.ru"), command);
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
                List.of("/usr/bin/firefox", "--kiosk", "https://example.org"),
                KioskBrowser.commandFor(Path.of("/usr/bin/firefox"), "https://example.org", 150, PROFILE, Optional.of(EXTENSION)));
    }

    @Test
    @DisplayName("an unknown browser gets the address alone")
    void unknownBrowsersGetTheAddressAlone() {
        assertEquals(
                List.of("/usr/bin/epiphany", "https://example.org"),
                KioskBrowser.commandFor(Path.of("/usr/bin/epiphany"), "https://example.org", 150, PROFILE, Optional.of(EXTENSION)));
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
}
