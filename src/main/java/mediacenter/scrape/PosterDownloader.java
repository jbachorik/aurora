package mediacenter.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.media.ArtworkResolver;

/**
 * Fetches a scraped title's poster into its folder — but never over artwork
 * already there, whoever put it there: a cover the user chose beats a scraped
 * one. Series and films share this, because a poster is a poster.
 *
 * <p>Written as {@code poster.jpg} (or {@code .png}, after the source),
 * which is the first name {@link ArtworkResolver} looks for — so a
 * downloaded poster appears on the shelf with no further wiring.
 */
final class PosterDownloader {

    private static final Logger LOG = Logger.getLogger(PosterDownloader.class.getName());

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Downloads the poster unless the folder already shows a cover. */
    void downloadIfMissing(Path folder, String posterUrl) {
        if (ArtworkResolver.selectCover(listFileNames(folder)).isPresent()) {
            return;
        }
        String extension = posterUrl.toLowerCase(Locale.ROOT).endsWith(".png") ? "png" : "jpg";
        Path target = folder.resolve("poster." + extension);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(posterUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length == 0) {
                LOG.log(Level.INFO, () -> "Poster download answered HTTP " + response.statusCode()
                        + " for " + posterUrl);
                return;
            }
            Files.write(target, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // The metadata is already saved; a poster that would not download
            // is retried the day the user deletes the metadata file, and the
            // tile falls back to its generated placeholder meanwhile.
            LOG.log(Level.INFO, e, () -> "Could not download the poster for " + folder);
        }
    }

    private static List<String> listFileNames(Path directory) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                Path name = entry.getFileName();
                if (name != null) {
                    names.add(name.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        return names;
    }
}
