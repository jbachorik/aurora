package mediacenter.scrape;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mediacenter.media.VideoFiles;

/**
 * Reads a folder into {@link SeriesEvidence}, deciding on the way whether it
 * is a series folder at all.
 *
 * <p>Two layouts are read, because both are common:
 *
 * <ul>
 *   <li>season folders — {@code Season 1}, {@code S02}, {@code Series 3}, or a
 *       bare {@code 1} — each holding that season's episodes;
 *   <li>a flat folder of episode files, whose season numbers come from their
 *       {@code SxxEyy} tags, or read as season one when the files carry only a
 *       "01 - " ordering prefix.
 * </ul>
 *
 * <p>A folder with neither yields nothing, which is the collector's way of
 * saying "not a series" — a folder that merely groups other folders, or a
 * shelf of films, is skipped before any network service is asked about it.
 * Every listing here blocks and belongs off the JavaFX thread.
 */
public final class SeriesEvidenceCollector {

    /** How many episode file names are quoted to the title-guessing model. */
    private static final int SAMPLE_LIMIT = 5;

    /**
     * {@code Season 1}, {@code S01}, {@code Series 2}, {@code Staffel 3}, or a
     * bare number. Anchored to the whole name: "Season 1 Extras" is a folder of
     * extras, and counting it as the season would poison the cross-check.
     */
    private static final Pattern SEASON_FOLDER = Pattern.compile(
            "(?i)^(?:season|series|staffel|s)[\\s._-]*(\\d{1,2})$|^(\\d{1,2})$");

    /**
     * The folder's evidence, or empty when it does not read as a series —
     * or simply cannot be listed, which over a share is an ordinary day.
     */
    public Optional<SeriesEvidence> collect(Path seriesFolder) {
        Path folderName = seriesFolder.getFileName();
        if (folderName == null) {
            // A filesystem root is a disk, not a series.
            return Optional.empty();
        }

        List<Path> seasonFolders = new ArrayList<>();
        List<String> looseVideoNames = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(seriesFolder)) {
            for (Path entry : stream) {
                Path entryName = entry.getFileName();
                if (entryName == null || VideoFiles.isJunk(entryName.toString())) {
                    continue;
                }
                if (Files.isDirectory(entry)) {
                    if (seasonNumberOf(entryName.toString()).isPresent()) {
                        seasonFolders.add(entry);
                    }
                } else if (VideoFiles.isVideoFileName(entryName.toString())) {
                    looseVideoNames.add(entryName.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }

        TreeMap<Integer, Integer> episodesPerSeason = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        for (Path seasonFolder : seasonFolders) {
            int season = seasonNumberOf(seasonFolder.getFileName().toString()).orElseThrow();
            int episodes = countEpisodes(seasonFolder, samples);
            if (episodes > 0) {
                // Two folders can name the same season ("S01" and "Season 1");
                // merging keeps the count honest instead of losing one of them.
                episodesPerSeason.merge(season, episodes, Integer::sum);
            }
        }

        // Loose files beside (or instead of) season folders: their tags say which
        // season they belong to, and a bare ordering prefix reads as season one.
        for (String name : looseVideoNames) {
            Optional<Integer> season = EpisodeTags.parse(name)
                    .map(EpisodeTags.SeasonEpisode::season)
                    .or(() -> EpisodeTags.parseOrderingPrefix(name).map(ignored -> 1));
            if (season.isPresent()) {
                episodesPerSeason.merge(season.get(), 1, Integer::sum);
                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add(name);
                }
            }
        }

        if (episodesPerSeason.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SeriesEvidence(folderName.toString(), episodesPerSeason, samples));
    }

    /** The number in a season folder's name, or empty when it is not one. */
    static Optional<Integer> seasonNumberOf(String folderName) {
        Matcher matcher = SEASON_FOLDER.matcher(folderName.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Optional.of(Integer.parseInt(number));
    }

    /** Counts a season folder's videos, quoting the first few names as samples. */
    private static int countEpisodes(Path seasonFolder, List<String> samples) {
        int episodes = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(seasonFolder)) {
            for (Path entry : stream) {
                Path entryName = entry.getFileName();
                if (entryName == null || !VideoFiles.isVideoFileName(entryName.toString())) {
                    continue;
                }
                if (Files.isRegularFile(entry)) {
                    episodes++;
                    if (samples.size() < SAMPLE_LIMIT) {
                        samples.add(entryName.toString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // An unreadable season contributes nothing; the seasons that could
            // be read still make their case.
            return 0;
        }
        return episodes;
    }
}
