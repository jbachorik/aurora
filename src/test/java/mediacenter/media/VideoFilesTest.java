package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VideoFilesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "movie.mkv", "movie.mp4", "movie.avi", "movie.mov", "movie.m4v",
            "movie.mpg", "movie.mpeg", "movie.ts", "movie.m2ts", "movie.webm"})
    @DisplayName("every extension named in the specification is recognized")
    void recognizesRequiredExtensions(String fileName) {
        assertTrue(VideoFiles.isVideoFileName(fileName), fileName);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MOVIE.MKV", "Movie.Mp4", "clip.WEBM"})
    void recognitionIsCaseInsensitive(String fileName) {
        assertTrue(VideoFiles.isVideoFileName(fileName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"poster.jpg", "notes.txt", "movie", "movie.", "subtitles.srt", "archive.zip"})
    void rejectsNonVideoFiles(String fileName) {
        assertFalse(VideoFiles.isVideoFileName(fileName));
    }

    @Test
    void recognizesPaths() {
        assertTrue(VideoFiles.isVideo(Path.of("/media/Movies/Alien (1979)/alien.mkv")));
        assertFalse(VideoFiles.isVideo(Path.of("/media/Movies/Alien (1979)/poster.jpg")));
    }

    @Test
    void extractsExtensionAndBaseName() {
        assertEquals("mkv", VideoFiles.extensionOf("Blade.Runner.2049.mkv"));
        assertEquals("", VideoFiles.extensionOf("README"));
        assertEquals("Blade.Runner.2049", VideoFiles.withoutExtension("Blade.Runner.2049.mkv"));
        assertEquals(".hidden", VideoFiles.withoutExtension(".hidden"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"desktop.ini", "Thumbs.db", "THUMBS.DB", ".DS_Store", ".hidden", "@eaDir"})
    void filtersJunk(String fileName) {
        assertTrue(VideoFiles.isJunk(fileName), fileName);
    }

    @Test
    void keepsOrdinaryNames() {
        assertFalse(VideoFiles.isJunk("Alien (1979)"));
        assertFalse(VideoFiles.isJunk("movie.mkv"));
    }
}
