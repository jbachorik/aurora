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
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * The little of TheTVDB's v4 API the scraper needs: log in, search for a
 * series, and count the episodes each of its seasons holds.
 *
 * <p>Authentication is a bearer token bought with the API key. The token is
 * fetched lazily, kept for the client's lifetime, and bought again once when a
 * request comes back 401 — TheTVDB expires tokens after a month, and a media
 * center left running should not need a restart to notice.
 *
 * <p>Failures are answered with empty results rather than exceptions: a scrape
 * is a background nicety, and the folder it was about plays exactly as well
 * without it.
 */
public final class TvdbClient {

    private static final Logger LOG = Logger.getLogger(TvdbClient.class.getName());

    public static final String DEFAULT_BASE_URL = "https://api4.thetvdb.com/v4";

    /** More results than this and the folder name was too vague to trust anyway. */
    private static final int SEARCH_LIMIT = 10;

    /**
     * Episode pages fetched per series before giving up. The API pages at 500
     * episodes, so this covers anything shy of a decades-long soap — and for a
     * soap the seasons counted so far are already plenty to match on.
     */
    private static final int EPISODE_PAGE_LIMIT = 10;

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;

    /** The current bearer token; volatile because scrapes may overlap some day. */
    private volatile String token;

    public TvdbClient(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    /** The base URL is a parameter so tests can point the client at themselves. */
    public TvdbClient(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Series matching a title, best first as TheTVDB ranks them. */
    public List<SeriesCandidate> searchSeries(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return get("/search?query=" + encoded + "&type=series&limit=" + SEARCH_LIMIT)
                .map(TvdbClient::parseSearch)
                .orElse(List.of());
    }

    /**
     * How many aired episodes each season of a series holds, in the default
     * (aired) order. Empty when the answer could not be fetched — which the
     * matcher treats as "no opinion", never as a mismatch.
     */
    public Optional<Map<Integer, Integer>> episodesPerSeason(long seriesId) {
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (int page = 0; page < EPISODE_PAGE_LIMIT; page++) {
            Optional<JsonObject> document = get("/series/" + seriesId + "/episodes/default?page=" + page);
            if (document.isEmpty()) {
                // Half an answer would read as seasons shorter than they are and
                // turn into mismatch strikes; no answer stays "no opinion".
                return Optional.empty();
            }
            countEpisodes(document.get(), counts);
            if (!hasNextPage(document.get())) {
                break;
            }
        }
        return Optional.of(counts);
    }

    // -- response shapes -----------------------------------------------------

    /** Reads a search response. Static and pure so the shape stays testable offline. */
    public static List<SeriesCandidate> parseSearch(JsonObject document) {
        List<SeriesCandidate> candidates = new ArrayList<>();
        for (JsonObject result : document.objectArray("data")) {
            // The search endpoint spells the id as a string.
            Optional<Long> id = result.longValue("tvdb_id");
            Optional<String> name = result.nonBlankString("name");
            if (id.isEmpty() || name.isEmpty()) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            result.get("aliases").ifPresent(value -> {
                if (value instanceof JsonArray(List<JsonValue> elements)) {
                    for (JsonValue element : elements) {
                        if (element instanceof JsonString(String alias) && !alias.isBlank()) {
                            aliases.add(alias);
                        }
                    }
                }
            });
            candidates.add(new SeriesCandidate(
                    id.get(),
                    name.get(),
                    aliases,
                    result.longValue("year").map(Long::intValue),
                    result.nonBlankString("overview"),
                    result.nonBlankString("status"),
                    result.nonBlankString("image_url")));
        }
        return List.copyOf(candidates);
    }

    /** Adds one episode page's counts. Static and pure for the same reason. */
    public static void countEpisodes(JsonObject document, Map<Integer, Integer> counts) {
        Optional<JsonValue> data = document.get("data");
        if (data.isEmpty() || !(data.get() instanceof JsonObject dataObject)) {
            return;
        }
        for (JsonObject episode : dataObject.objectArray("episodes")) {
            episode.longValue("seasonNumber")
                    .ifPresent(season -> counts.merge(season.intValue(), 1, Integer::sum));
        }
    }

    /**
     * Whether the response points at a further page. Only the pointer's
     * presence matters — the next page's number is the loop's to count.
     */
    static boolean hasNextPage(JsonObject document) {
        return document.get("links")
                .filter(value -> value instanceof JsonObject links
                        && links.nonBlankString("next").isPresent())
                .isPresent();
    }

    // -- transport ------------------------------------------------------------

    /** One authenticated GET, buying a fresh token when the old one has expired. */
    private Optional<JsonObject> get(String path) {
        try {
            String bearer = currentToken();
            if (bearer == null) {
                return Optional.empty();
            }
            HttpResponse<String> response = send(path, bearer);
            if (response.statusCode() == 401) {
                token = null;
                bearer = currentToken();
                if (bearer == null) {
                    return Optional.empty();
                }
                response = send(path, bearer);
            }
            if (response.statusCode() != 200) {
                int status = response.statusCode();
                LOG.log(Level.INFO, () -> "TheTVDB answered HTTP " + status + " for " + path);
                return Optional.empty();
            }
            return Optional.of(Json.parseObject(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | JsonException | RuntimeException e) {
            LOG.log(Level.INFO, e, () -> "TheTVDB request failed for " + path);
            return Optional.empty();
        }
    }

    private HttpResponse<String> send(String path, String bearer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(RESPONSE_TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String currentToken() throws IOException, InterruptedException {
        String current = token;
        if (current != null) {
            return current;
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/login"))
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(new JsonObject(Map.of("apikey", new JsonString(apiKey)))),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            LOG.log(Level.WARNING, () -> "TheTVDB login failed with HTTP " + response.statusCode()
                    + " — check the API key in Settings");
            return null;
        }
        try {
            Optional<String> fresh = Json.parseObject(response.body()).get("data")
                    .flatMap(value -> value instanceof JsonObject data
                            ? data.nonBlankString("token")
                            : Optional.empty());
            token = fresh.orElse(null);
            return token;
        } catch (JsonException e) {
            LOG.log(Level.WARNING, "TheTVDB login answered something other than JSON", e);
            return null;
        }
    }
}
