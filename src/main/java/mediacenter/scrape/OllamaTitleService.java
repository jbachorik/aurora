package mediacenter.scrape;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import mediacenter.json.Json;
import mediacenter.json.JsonException;
import mediacenter.json.JsonValue;
import mediacenter.json.JsonValue.JsonArray;
import mediacenter.json.JsonValue.JsonBoolean;
import mediacenter.json.JsonValue.JsonNumber;
import mediacenter.json.JsonValue.JsonObject;
import mediacenter.json.JsonValue.JsonString;

/**
 * Asks an Ollama model what a series folder is actually called.
 *
 * <p>Folder names are written by rippers, not librarians:
 * {@code Game.of.Thrones.S01-S08.COMPLETE.1080p.BluRay.x265} names the codec
 * as loudly as the series. Cleaning that with rules alone means maintaining
 * the rules forever; a small language model reads past the noise. The model
 * is handed the folder name <em>and</em> what the folder holds — episodes per
 * season, a few episode file names — so its guess is grounded in the same
 * evidence the TheTVDB match is later checked against.
 *
 * <p>Talks to the standard Ollama chat API, which is the same whether the
 * endpoint is Ollama's hosted service (an API key and its free tier) or an
 * Ollama running on a machine in the house (no key at all).
 *
 * <p>The guess is advisory: every failure — endpoint down, key rejected,
 * model rambling — is logged and answered with {@code empty}, and the scraper
 * falls back to searching with the cleaned folder name.
 */
public final class OllamaTitleService {

    private static final Logger LOG = Logger.getLogger(OllamaTitleService.class.getName());

    /**
     * Generous on purpose: a cold model is loaded on first use, and the hosted
     * free tier queues. A scrape runs in the background where a slow answer
     * costs nothing visible; a lost one costs the better search term.
     */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(90);

    private final String endpoint;
    private final Optional<String> apiKey;
    private final String model;
    private final HttpClient client;

    public OllamaTitleService(String endpoint, Optional<String> apiKey, String model) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The model's reading of a movie folder, or empty when anything went wrong. */
    public Optional<TitleGuess> guessMovieTitle(MovieEvidence evidence) {
        return ask(buildMoviePrompt(evidence), evidence.folderName());
    }

    /** The model's reading of the evidence, or empty when anything went wrong. */
    public Optional<TitleGuess> guessTitle(SeriesEvidence evidence) {
        return ask(buildPrompt(evidence), evidence.folderName());
    }

    private Optional<TitleGuess> ask(String prompt, String folderName) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/api/chat"))
                    .timeout(RESPONSE_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            Json.write(requestBody(prompt)), StandardCharsets.UTF_8));
            apiKey.ifPresent(key -> request.header("Authorization", "Bearer " + key));

            HttpResponse<String> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                // Worth a warning and the server's own words: the hosted
                // service turns away anything but an API key made on its
                // keys page, and "401" alone sends nobody there.
                LOG.log(Level.WARNING, () -> "Ollama refused the credential (HTTP " + status
                        + ") — check the Ollama API key in Settings; the hosted service needs "
                        + "an API key from ollama.com/settings/keys. Server said: "
                        + briefBody(response.body()));
                return Optional.empty();
            }
            if (status != 200) {
                LOG.log(Level.INFO, () -> "Ollama answered HTTP " + status
                        + " for \"" + folderName + "\": " + briefBody(response.body()));
                return Optional.empty();
            }
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.log(Level.INFO, e, () -> "Ollama title guess failed for \"" + folderName + "\"");
            return Optional.empty();
        }
    }

    private JsonObject requestBody(String prompt) {
        Map<String, JsonValue> message = new LinkedHashMap<>();
        message.put("role", new JsonString("user"));
        message.put("content", new JsonString(prompt));

        Map<String, JsonValue> body = new LinkedHashMap<>();
        body.put("model", new JsonString(model));
        body.put("messages", new JsonArray(List.of(new JsonObject(message))));
        body.put("stream", new JsonBoolean(false));
        // Constrains the reply to be JSON at all; the prompt says which JSON.
        body.put("format", new JsonString("json"));
        body.put("options", new JsonObject(Map.of("temperature", new JsonNumber(0))));
        return new JsonObject(body);
    }

    /**
     * The question put to the model, built from nothing but the evidence.
     * Public and pure so tests can hold it still.
     */
    public static String buildPrompt(SeriesEvidence evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A media library folder holds one television series. ")
                .append("Infer the series' official title from the folder name and its contents.\n")
                .append("Folder name: ").append(evidence.folderName()).append('\n');
        prompt.append("Episodes on disk:");
        evidence.episodesPerSeason().forEach((season, count) ->
                prompt.append(" season ").append(season).append(": ").append(count).append(" episodes;"));
        prompt.append('\n');
        if (!evidence.sampleEpisodeNames().isEmpty()) {
            prompt.append("Some episode file names:\n");
            for (String name : evidence.sampleEpisodeNames()) {
                prompt.append("- ").append(name).append('\n');
            }
        }
        prompt.append("Answer with JSON only, exactly {\"title\": string, \"year\": number or null} — ")
                .append("the title as officially released, without quality tags or release-group noise, ")
                .append("and the year of first airing if you are confident, else null.");
        return prompt.toString();
    }

    /**
     * The same question for one film. Public and pure like
     * {@link #buildPrompt(SeriesEvidence)}, and for the same reason.
     */
    public static String buildMoviePrompt(MovieEvidence evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("A media library folder holds one film. ")
                .append("Infer the film's official title from the names below.\n")
                .append("Folder name: ").append(evidence.folderName()).append('\n')
                .append("Video file name: ").append(evidence.videoFileName()).append('\n');
        prompt.append("Answer with JSON only, exactly {\"title\": string, \"year\": number or null} — ")
                .append("the title as officially released, without quality tags or release-group noise, ")
                .append("and the year of release if you are confident, else null. ")
                .append("Take care with titles that contain numbers or years of their own.");
        return prompt.toString();
    }

    /**
     * Reads the guess out of the chat response. Public and pure so the shapes
     * models actually produce — fenced, chatty, or clean — stay covered by tests.
     */
    public static Optional<TitleGuess> parseResponse(String responseBody) {
        try {
            JsonObject response = Json.parseObject(responseBody);
            Optional<String> content = response.get("message")
                    .flatMap(value -> value instanceof JsonObject message
                            ? message.nonBlankString("content")
                            : Optional.empty());
            if (content.isEmpty()) {
                return Optional.empty();
            }
            JsonObject answer = Json.parseObject(withoutCodeFence(content.get()));
            Optional<String> title = answer.nonBlankString("title");
            if (title.isEmpty()) {
                return Optional.empty();
            }
            Optional<Integer> year = answer.longValue("year")
                    .map(Long::intValue)
                    .filter(OllamaTitleService::isPlausibleYear);
            return Optional.of(new TitleGuess(title.get(), year));
        } catch (JsonException | RuntimeException e) {
            LOG.log(Level.FINE, "Unreadable Ollama response", e);
            return Optional.empty();
        }
    }

    /**
     * Models asked for JSON still love to wrap it in a Markdown fence; the
     * fence is decoration, not disobedience, so it is peeled off rather than
     * failing the guess.
     */
    private static String withoutCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
    }

    /** Television with a year outside this range is a hallucination, not a fact. */
    private static boolean isPlausibleYear(int year) {
        return year >= 1930 && year <= 2100;
    }

    /** An error body's first line, bounded — servers explain, logs should not scroll. */
    private static String briefBody(String body) {
        if (body == null || body.isBlank()) {
            return "(no body)";
        }
        String firstLine = body.strip().lines().findFirst().orElse("");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";
    }

    private static String trimTrailingSlash(String endpoint) {
        String trimmed = endpoint.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public String toString() {
        return "Ollama at " + endpoint + " (" + model + ")";
    }
}
