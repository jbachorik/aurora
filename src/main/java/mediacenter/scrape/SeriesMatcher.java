package mediacenter.scrape;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Decides which of TheTVDB's candidates — if any — is the series on disk.
 *
 * <p>Deliberately conservative, in the house tradition: a wrong poster and a
 * wrong synopsis stuck to a folder are worse than none, so a candidate has to
 * earn the match and ambiguity loses it. Three things weigh in:
 *
 * <ul>
 *   <li><b>the title</b>, compared against the candidate's name and every
 *       alias — the language model's guess and the folder name both count,
 *       whichever reads closer;
 *   <li><b>the year</b>, when both sides name one;
 *   <li><b>the shape of the thing</b>: a season on disk holding more episodes
 *       than the candidate's season ever aired is a strike, while holding
 *       fewer is fine — half-ripped seasons are normal, longer ones are not.
 * </ul>
 *
 * <p>Everything here is pure and static: the network fetches, this judges.
 */
public final class SeriesMatcher {

    /** Below this the names simply are not the same title, whatever else agrees. */
    static final double MINIMUM_TITLE_SCORE = 0.6;

    /** The combined score a match must reach. */
    static final double MINIMUM_SCORE = 0.6;

    /**
     * How clearly the winner must beat the runner-up. Two candidates this close
     * — a remake and its original, say — is a question for a person, and the
     * safe answer to a question nobody is around to ask is "no match".
     */
    static final double MINIMUM_LEAD = 0.05;

    private SeriesMatcher() {
    }

    /** One candidate together with what TheTVDB says its seasons hold. */
    public record Candidate(SeriesCandidate series, Optional<Map<Integer, Integer>> episodesPerSeason) {
    }

    /**
     * The one candidate worth writing to disk, or empty when none is, or when
     * two are too close to call.
     */
    public static Optional<SeriesCandidate> pick(
            SeriesEvidence evidence, Optional<TitleGuess> guess, List<Candidate> candidates) {

        SeriesCandidate best = null;
        double bestScore = 0;
        double secondScore = 0;
        for (Candidate candidate : candidates) {
            double candidateScore = score(evidence, guess, candidate);
            if (candidateScore > bestScore) {
                secondScore = bestScore;
                bestScore = candidateScore;
                best = candidate.series();
            } else if (candidateScore > secondScore) {
                secondScore = candidateScore;
            }
        }
        if (best == null || bestScore < MINIMUM_SCORE || bestScore - secondScore < MINIMUM_LEAD) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    /**
     * The candidate's overall score in {@code [0, 1]}. The title carries most
     * of the weight — it is the only signal that is always present — with the
     * season shape and the year adjusting it.
     */
    static double score(SeriesEvidence evidence, Optional<TitleGuess> guess, Candidate candidate) {
        double title = titleScore(candidate.series(), guess, evidence.folderName());
        if (title < MINIMUM_TITLE_SCORE) {
            return 0;
        }
        double structure = candidate.episodesPerSeason()
                .map(counts -> structureScore(evidence, counts))
                // No episode data is no opinion: the title must stand alone.
                .orElse(0.5);
        double score = 0.7 * title + 0.3 * structure;
        if (guess.flatMap(TitleGuess::year).isPresent() && candidate.series().year().isPresent()) {
            int guessed = guess.get().year().get();
            int actual = candidate.series().year().get();
            // Off by one is forgiven — first-airing years straddle new year
            // often enough that the two sources legitimately disagree.
            score += Math.abs(guessed - actual) <= 1 ? 0.1 : -0.1;
        }
        return Math.clamp(score, 0, 1);
    }

    /** The best similarity the candidate's name or any alias manages. */
    static double titleScore(SeriesCandidate candidate, Optional<TitleGuess> guess, String folderName) {
        double best = 0;
        for (String candidateName : names(candidate)) {
            best = Math.max(best, similarity(candidateName, folderName));
            if (guess.isPresent()) {
                best = Math.max(best, similarity(candidateName, guess.get().title()));
            }
        }
        return best;
    }

    private static List<String> names(SeriesCandidate candidate) {
        List<String> names = new ArrayList<>();
        names.add(candidate.name());
        names.addAll(candidate.aliases());
        return names;
    }

    /**
     * How well the seasons on disk fit inside the candidate's: the fraction of
     * local seasons the candidate can contain. A season the candidate does not
     * have, or has with fewer episodes than the disk holds, cannot be this
     * series' season; a season holding fewer than aired is an unfinished rip
     * and entirely ordinary.
     */
    static double structureScore(SeriesEvidence evidence, Map<Integer, Integer> tvdbEpisodesPerSeason) {
        int seasons = 0;
        int compatible = 0;
        for (Map.Entry<Integer, Integer> local : evidence.episodesPerSeason().entrySet()) {
            seasons++;
            Integer aired = tvdbEpisodesPerSeason.get(local.getKey());
            if (aired != null && local.getValue() <= aired) {
                compatible++;
            }
        }
        return seasons == 0 ? 0.5 : (double) compatible / seasons;
    }

    /**
     * Similarity of two titles in {@code [0, 1]}: one minus the edit distance's
     * share of the longer name, after both are normalised. Not a clever metric,
     * but a legible one — and legible is what a threshold can be tuned against.
     */
    static double similarity(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        if (left.equals(right)) {
            return 1;
        }
        int distance = editDistance(left, right);
        return 1.0 - (double) distance / Math.max(left.length(), right.length());
    }

    /**
     * Lower-cased and stripped to letters, digits and single spaces, with a
     * leading article dropped — "The Office" and "office" are the same claim.
     * Trailing bracketed years and quality noise mostly dissolve into spaces
     * here; what survives, the edit distance absorbs.
     */
    static String normalize(String title) {
        String normalized = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        // A year at the end of a folder name ("Chernobyl 2019") is metadata,
        // not title; TheTVDB's names carry it only to tell remakes apart, and
        // the year is judged separately where it is actually known.
        normalized = normalized.replaceAll("\\s+(19|20)\\d{2}$", "");
        if (normalized.startsWith("the ")) {
            normalized = normalized.substring(4);
        }
        return normalized.trim();
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
