package mediacenter.playback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * Turns a web page into an address VLC can play, by asking yt-dlp.
 *
 * <p>yt-dlp knows the players hundreds of sites embed — VK's, the one Mosfilm
 * uses, among them — and its generic extractor follows an embed it has never
 * met. Delegating means the site knowledge that rots is maintained there, not
 * here: when a page stops resolving, updating yt-dlp is the fix, not a release
 * of the media center.
 *
 * <p>This never touches DRM: a site whose streams are encrypted simply yields
 * nothing playable, and the kiosk browser remains the way to watch it.
 *
 * <p>The command is argument-list arithmetic like the launchers'; the answer is
 * yt-dlp's own JSON, of which three things matter: the stream address, the
 * title, and the HTTP headers the host insists on seeing.
 */
public final class StreamResolver {

    private static final Logger LOG = Logger.getLogger(StreamResolver.class.getName());

    /** Longer means a stuck site blocks the button; shorter fails slow pages. */
    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(45);

    /** Enough for the JSON of any single film, guarding a runaway process. */
    private static final int MAX_OUTPUT_BYTES = 32 * 1024 * 1024;

    /** Where yt-dlp tends to be when the PATH the launcher sees omits it. */
    private static final List<String> WELL_KNOWN_DIRECTORIES = List.of(
            System.getProperty("user.home", "") + "/.local/bin",
            "/usr/local/bin",
            "/opt/homebrew/bin");

    /** What the stream needs to play: the address, plus how to ask for it. */
    public record ResolvedStream(String url, Optional<String> title, Map<String, String> httpHeaders) {

        public ResolvedStream {
            httpHeaders = Map.copyOf(httpHeaders);
        }
    }

    private final Path ytDlpExecutable;

    public StreamResolver(Path ytDlpExecutable) {
        this.ytDlpExecutable = ytDlpExecutable;
    }

    /**
     * The command line a resolution runs, exposed for logging and tests.
     *
     * <p>{@code -f b} asks for the best format that is one address — a muxed
     * file or an HLS manifest. The split video-plus-audio pairs yt-dlp itself
     * prefers are two addresses, which is one more than a player can be handed.
     */
    public static List<String> commandFor(Path ytDlpExecutable, String pageUrl) {
        return List.of(
                ytDlpExecutable.toString(),
                "--dump-json",
                "--no-playlist",
                "--no-warnings",
                "-f", "b",
                // Everything after is the address, dashes and all.
                "--", pageUrl);
    }

    /**
     * Asks yt-dlp what the page plays. Empty when it has no answer — an
     * unsupported site, a DRM stream, a network problem, or no yt-dlp worth
     * the name — and the caller falls back to the browser it already has.
     */
    public Optional<ResolvedStream> resolve(String pageUrl) {
        List<String> command = commandFor(ytDlpExecutable, pageUrl);
        LOG.log(Level.INFO, () -> "Resolving stream: " + String.join(" ", command));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // The reaper turns a hang into an orderly failure: killing the
            // process ends the output at EOF and the read below returns.
            Thread reaper = Thread.ofVirtual().start(() -> {
                try {
                    if (!process.waitFor(RESOLVE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        LOG.log(Level.WARNING, () -> "yt-dlp took over " + RESOLVE_TIMEOUT.toSeconds()
                                + "s on " + pageUrl + ", giving up");
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    // The resolution finished first; nothing to reap.
                }
            });
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES);
            int exitCode = process.waitFor();
            reaper.interrupt();
            if (exitCode != 0) {
                LOG.log(Level.INFO, () -> "yt-dlp found nothing playable on " + pageUrl
                        + " (exit code " + exitCode + ")");
                return Optional.empty();
            }
            return parse(new String(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "yt-dlp could not be started", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Reads yt-dlp's JSON; empty when there is no stream address in it. */
    static Optional<ResolvedStream> parse(String json) {
        try {
            JsonObject document = Json.parseObject(json);
            Optional<String> url = document.nonBlankString("url");
            if (url.isEmpty()) {
                LOG.info("yt-dlp answered without a stream address");
                return Optional.empty();
            }
            return Optional.of(new ResolvedStream(
                    url.get(), document.nonBlankString("title"), headersOf(document)));
        } catch (JsonException e) {
            LOG.log(Level.WARNING, "yt-dlp's answer was not JSON", e);
            return Optional.empty();
        }
    }

    private static Map<String, String> headersOf(JsonObject document) {
        Optional<JsonValue> headers = document.get("http_headers");
        if (headers.isEmpty() || !(headers.get() instanceof JsonObject headerObject)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        headerObject.members().forEach((name, value) -> {
            if (value instanceof JsonString(String text)) {
                result.put(name, text);
            }
        });
        return result;
    }

    /**
     * Where yt-dlp is: the configured path when one is set and exists, else
     * the PATH, else the usual install spots the PATH of a desktop-launched
     * application tends to miss. Empty means it is not installed, which the
     * caller turns into one plain sentence.
     */
    public static Optional<Path> locate(Optional<Path> configured) {
        if (configured.isPresent()) {
            // A configured path that is broken is reported as missing rather
            // than silently shadowed by a PATH lookup the user tried to override.
            return configured.filter(Files::isRegularFile);
        }
        String pathVariable = System.getenv("PATH");
        String separator = System.getProperty("path.separator", ":");
        if (pathVariable != null) {
            for (String directory : pathVariable.split(java.util.regex.Pattern.quote(separator))) {
                Optional<Path> found = executableIn(directory);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        for (String directory : WELL_KNOWN_DIRECTORIES) {
            Optional<Path> found = executableIn(directory);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> executableIn(String directory) {
        if (directory.isBlank()) {
            return Optional.empty();
        }
        for (String name : List.of("yt-dlp", "yt-dlp.exe")) {
            try {
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (RuntimeException e) {
                // An unparseable PATH entry is somebody else's problem.
            }
        }
        return Optional.empty();
    }
}
