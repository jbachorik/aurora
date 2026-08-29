package mediacenter.scrape;

import java.util.Optional;

/**
 * What a movie folder says about itself, read entirely from disk.
 *
 * <p>Slimmer than {@link SeriesEvidence}, because a film has less shape to
 * read: the folder's name, the one video file's name, and — when either of
 * them carries one — the release year. The year is the cross-check a film
 * gets in place of episodes per season, and it is what tells a remake from
 * its original, so it is dug out with some care.
 */
public record MovieEvidence(
        String folderName,
        String videoFileName,
        Optional<Integer> yearHint) {

    public MovieEvidence {
        if (folderName == null || folderName.isBlank()) {
            throw new IllegalArgumentException("Movie folder name must not be blank");
        }
        if (videoFileName == null || videoFileName.isBlank()) {
            throw new IllegalArgumentException("Movie evidence must carry the video's file name");
        }
        yearHint = yearHint == null ? Optional.empty() : yearHint;
    }
}
