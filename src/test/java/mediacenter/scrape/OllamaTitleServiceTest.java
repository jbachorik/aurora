package mediacenter.scrape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OllamaTitleServiceTest {

    private static SeriesEvidence evidence() {
        TreeMap<Integer, Integer> seasons = new TreeMap<>();
        seasons.put(1, 7);
        seasons.put(2, 13);
        return new SeriesEvidence(
                "Breaking.Bad.S01-S02.1080p.BluRay",
                seasons,
                List.of("Breaking.Bad.S01E01.Pilot.mkv"));
    }

    @Test
    @DisplayName("the prompt quotes the evidence the answer is later checked against")
    void thePromptCarriesTheEvidence() {
        String prompt = OllamaTitleService.buildPrompt(evidence());

        assertTrue(prompt.contains("Breaking.Bad.S01-S02.1080p.BluRay"));
        assertTrue(prompt.contains("season 1: 7 episodes"));
        assertTrue(prompt.contains("season 2: 13 episodes"));
        assertTrue(prompt.contains("Breaking.Bad.S01E01.Pilot.mkv"));
        assertTrue(prompt.contains("\"title\""));
    }

    @Test
    @DisplayName("the movie prompt quotes both names and asks for a release year")
    void theMoviePromptCarriesTheEvidence() {
        String prompt = OllamaTitleService.buildMoviePrompt(new MovieEvidence(
                "Blade Runner 2049 (2017)", "Blade.Runner.2049.2017.mkv",
                Optional.of(2017), Optional.empty()));

        assertTrue(prompt.contains("one film"));
        assertTrue(prompt.contains("Blade Runner 2049 (2017)"));
        assertTrue(prompt.contains("Blade.Runner.2049.2017.mkv"));
        assertTrue(prompt.contains("\"title\""));
    }

    @Test
    @DisplayName("a clean JSON answer is read as title and year")
    void parsesACleanAnswer() {
        Optional<TitleGuess> guess = OllamaTitleService.parseResponse("""
                {"message": {"role": "assistant",
                 "content": "{\\"title\\": \\"Breaking Bad\\", \\"year\\": 2008}"}}
                """);

        assertEquals("Breaking Bad", guess.orElseThrow().title());
        assertEquals(Optional.of(2008), guess.orElseThrow().year());
    }

    @Test
    @DisplayName("a fenced answer is a JSON answer wearing Markdown")
    void parsesAFencedAnswer() {
        Optional<TitleGuess> guess = OllamaTitleService.parseResponse("""
                {"message": {"role": "assistant",
                 "content": "```json\\n{\\"title\\": \\"Chernobyl\\", \\"year\\": null}\\n```"}}
                """);

        assertEquals("Chernobyl", guess.orElseThrow().title());
        assertEquals(Optional.empty(), guess.orElseThrow().year());
    }

    @Test
    @DisplayName("what cannot be read is no guess, never an error")
    void refusesTheUnreadable() {
        assertTrue(OllamaTitleService.parseResponse("not json at all").isEmpty());
        assertTrue(OllamaTitleService.parseResponse("{}").isEmpty());
        assertTrue(OllamaTitleService.parseResponse(
                "{\"message\": {\"content\": \"I think it is Breaking Bad!\"}}").isEmpty());
        assertTrue(OllamaTitleService.parseResponse(
                "{\"message\": {\"content\": \"{\\\"title\\\": \\\"\\\"}\"}}").isEmpty());
    }

    @Test
    @DisplayName("a hallucinated year is dropped; the title survives it")
    void dropsImplausibleYears() {
        Optional<TitleGuess> guess = OllamaTitleService.parseResponse("""
                {"message": {"content": "{\\"title\\": \\"Breaking Bad\\", \\"year\\": 8}"}}
                """);

        assertEquals("Breaking Bad", guess.orElseThrow().title());
        assertEquals(Optional.empty(), guess.orElseThrow().year());
    }
}
