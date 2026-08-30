package mediacenter.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.json.JsonValue.JsonObject;

/**
 * The little of TMDB's v3 API the scraper needs — the same four questions
 * {@link TvdbClient} answers, from the other big movie database.
 *
 * <p>Simpler transport than its sibling: there is no login and no token to
 * refresh. TMDB hands out two kinds of credential and both are accepted here
 * — the v4 "API Read Access Token" (a long dotted JWT, sent as a bearer) and
 * the short v3 "API Key" (sent as a query parameter); which one was pasted is
 * recognisable from its shape, so the user never has to say.
 *
 * <p>Simpler data, too: a series' record carries its seasons with their
 * episode counts outright — no paging through episodes — and a film's record
 * carries its runtime. What the search results lack is aliases; the original-
 * language title stands in as the one alias worth having, which is what lets
 * "La Casa de Papel" find Money Heist.
 *
 * <p>Failures are answered with empty results rather than exceptions, exactly
 * like the sibling: a scrape is a background nicety.
 */
public final class TmdbClient implements MetadataProvider {

    private static final Logger LOG = Logger.getLogger(TmdbClient.class.getName());

    public static final String DEFAULT_BASE_URL = "https://api.themoviedb.org/3";

    /**
     * Where poster paths resolve to. {@code w500} rather than the original:
     * a tile's poster column never shows more than that, and originals run
     * to several megabytes each over the share.
     */
    static final String IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500";

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;

    public TmdbClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    /** The base URL is a parameter so tests can point the client at themselves. */
    public TmdbClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "TMDB";
    }

    @Override
    public List<TitleCandidate> searchSeries(String query) {
        return search("/search/tv", query, TmdbClient::parseSeriesSearch);
    }

    @Override
    public List<TitleCandidate> searchMovies(String query) {
        return search("/search/movie", query, TmdbClient::parseMovieSearch);
    }

    private List<TitleCandidate> search(
            String path, String query, Function<JsonObject, List<TitleCandidate>> parser) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return get(path + "?query=" + encoded + "&include_adult=false&page=1")
                .map(parser)
                .orElse(List.of());
    }

    @Override
    public Optional<Map<Integer, Integer>> episodesPerSeason(long seriesId) {
        return get("/tv/" + seriesId).flatMap(TmdbClient::parseEpisodesPerSeason);
    }

    @Override
    public Optional<Integer> movieRuntimeMinutes(long movieId) {
        return get("/movie/" + movieId).flatMap(TmdbClient::parseMovieRuntime);
    }

    // -- response shapes -----------------------------------------------------

    /** Reads a TV search response. Static and pure so the shape stays testable offline. */
    public static List<TitleCandidate> parseSeriesSearch(JsonObject document) {
        return parseSearch(document, "name", "original_name", "first_air_date");
    }

    /** Reads a movie search response; the fields are named differently, nothing else is. */
    public static List<TitleCandidate> parseMovieSearch(JsonObject document) {
        return parseSearch(document, "title", "original_title", "release_date");
    }

    private static List<TitleCandidate> parseSearch(
            JsonObject document, String nameKey, String originalNameKey, String dateKey) {
        List<TitleCandidate> candidates = new ArrayList<>();
        for (JsonObject result : document.objectArray("results")) {
            Optional<Long> id = result.longValue("id");
            Optional<String> name = result.nonBlankString(nameKey);
            if (id.isEmpty() || name.isEmpty()) {
                continue;
            }
            // The original-language title is the only alias the search offers,
            // and only worth carrying when it actually differs.
            List<String> aliases = result.nonBlankString(originalNameKey)
                    .filter(original -> !original.equals(name.get()))
                    .map(List::of)
                    .orElse(List.of());
            candidates.add(new TitleCandidate(
                    id.get(),
                    name.get(),
                    aliases,
                    yearOf(result.nonBlankString(dateKey)),
                    result.nonBlankString("overview"),
                    // The search knows no status; the matcher never asks.
                    Optional.empty(),
                    result.nonBlankString("poster_path").map(path -> IMAGE_BASE_URL + path)));
        }
        return List.copyOf(candidates);
    }

    /**
     * Reads season sizes out of a series' record — carried whole, specials as
     * season zero, exactly the shape the cross-check wants.
     */
    public static Optional<Map<Integer, Integer>> parseEpisodesPerSeason(JsonObject document) {
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (JsonObject season : document.objectArray("seasons")) {
            Optional<Long> number = season.longValue("season_number");
            Optional<Long> episodes = season.longValue("episode_count");
            if (number.isPresent() && episodes.isPresent() && episodes.get() > 0) {
                counts.merge(number.get().intValue(), episodes.get().intValue(), Integer::sum);
            }
        }
        return counts.isEmpty() ? Optional.empty() : Optional.of(counts);
    }

    /** Reads the runtime out of a movie's record. Static and pure. */
    public static Optional<Integer> parseMovieRuntime(JsonObject document) {
        return document.longValue("runtime")
                .map(Long::intValue)
                .filter(minutes -> minutes > 0);
    }

    /** The date's leading year — TMDB writes {@code 2008-01-20}, or nothing at all. */
    static Optional<Integer> yearOf(Optional<String> date) {
        return date.filter(text -> text.length() >= 4)
                .map(text -> text.substring(0, 4))
                .filter(text -> text.chars().allMatch(Character::isDigit))
                .map(Integer::parseInt);
    }

    // -- transport ------------------------------------------------------------

    private Optional<JsonObject> get(String path) {
        try {
            // A v4 token is a dotted JWT and rides in the header; the short v3
            // key knows only the query string. The shape says which was pasted.
            boolean bearer = apiKey.contains(".");
            String separator = path.contains("?") ? "&" : "?";
            String uri = baseUrl + path
                    + (bearer ? "" : separator + "api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(RESPONSE_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            if (bearer) {
                request.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                int status = response.statusCode();
                LOG.log(Level.INFO, () -> "TMDB answered HTTP " + status + " for " + path
                        + (status == 401 ? " — check the API key in Settings" : ""));
                return Optional.empty();
            }
            return Optional.of(Json.parseObject(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | JsonException | RuntimeException e) {
            LOG.log(Level.INFO, e, () -> "TMDB request failed for " + path);
            return Optional.empty();
        }
    }
}
