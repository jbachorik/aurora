package mediacenter.media;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhotoFilesTest {

    @Test
    void recognisesTheFormatsJavaFxCanDecode() {
        assertTrue(PhotoFiles.isPhotoFileName("beach.jpg"));
        assertTrue(PhotoFiles.isPhotoFileName("beach.JPEG"));
        assertTrue(PhotoFiles.isPhotoFileName("poster.png"));
        assertTrue(PhotoFiles.isPhotoFileName("animation.gif"));
        assertTrue(PhotoFiles.isPhotoFileName("scan.bmp"));
    }

    @Test
    @DisplayName("formats JavaFX cannot decode are not photographs, whatever they contain")
    void rejectsFormatsThatCannotBeDecoded() {
        assertFalse(PhotoFiles.isPhotoFileName("IMG_0001.heic"));
        assertFalse(PhotoFiles.isPhotoFileName("IMG_0001.HEIC"));
        assertFalse(PhotoFiles.isPhotoFileName("photo.webp"));
        assertFalse(PhotoFiles.isPhotoFileName("scan.tiff"));
    }

    @Test
    void isNotConfusedByVideo() {
        assertFalse(PhotoFiles.isPhotoFileName("movie.mkv"));
        assertFalse(PhotoFiles.isPhotoFileName("noextension"));
    }

    @Test
    @DisplayName("junk and dot-files are not photographs")
    void treatsJunkAsNotAPhotograph() {
        assertFalse(PhotoFiles.isPhotoFileName("Thumbs.db"));
        assertFalse(PhotoFiles.isPhotoFileName(".hidden.jpg"));
    }

    @Test
    void acceptsAPath() {
        assertTrue(PhotoFiles.isPhoto(Path.of("/media/Holidays/beach.jpg")));
        assertFalse(PhotoFiles.isPhoto(null));
    }
}
