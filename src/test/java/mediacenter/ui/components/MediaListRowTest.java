package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaItem;

/**
 * The caption pipeline alone — pure, so no toolkit is needed. The items are
 * built the way {@code MediaScanner} builds them: the display name is
 * {@link DisplayNames}' reading of the on-disk name.
 */
class MediaListRowTest {

    private static MediaItem video(String fileName) {
        Path path = Path.of("/media/TV/parent/" + fileName);
        return MediaItem.video(path, DisplayNames.forFile(path), Optional.empty(), 0L);
    }

    @Test
    @DisplayName("a line carries the display name: no extension, no dots, no leading tag")
    void captionsWithTheDisplayName() {
        assertEquals("Heat 1995",
                MediaListRow.captionFor(video("Heat.1995.mkv"), List.of()));
        assertEquals("The Wolf and the Lion",
                MediaListRow.captionFor(video("S01E05-The Wolf and the Lion.mkv"), List.of()));
        assertEquals("the kingsroad",
                MediaListRow.captionFor(video("s01e02.the.kingsroad.mkv"), List.of()));
    }

    @Test
    @DisplayName("dropping the parent's echo may uncover a tag, which then goes too")
    void theEchoAndTheTagItHidGoTogether() {
        assertEquals("Pilot", MediaListRow.captionFor(
                video("Breaking.Bad.S01E01.Pilot.mkv"),
                List.of("Breaking Bad", "TV")));
    }

    @Test
    @DisplayName("a caption is never emptied into nothing")
    void neverCaptionsWithNothing() {
        // The whole name is the parent's echo: the guard keeps it whole.
        assertEquals("Breaking Bad", MediaListRow.captionFor(
                video("Breaking.Bad.mkv"), List.of("Breaking Bad")));
        // The whole name is its tag: the tag is better than a blank line.
        assertEquals("S01E05", MediaListRow.captionFor(video("S01E05.mkv"), List.of()));
    }

    @Test
    @DisplayName("a folder line reads by its cleaned name as well")
    void captionsDirectories() {
        Path path = Path.of("/media/TV/The_Wire");
        MediaItem folder = MediaItem.directory(
                path, DisplayNames.forDirectory(path), Optional.empty(), 0L);
        assertEquals("The Wire", MediaListRow.captionFor(folder, List.of("TV")));
    }
}
