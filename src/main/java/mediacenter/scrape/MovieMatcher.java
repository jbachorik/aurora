package mediacenter.scrape;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import mediacenter.media.VideoFiles;

/**
 * Decides which of TheTVDB's movie candidates — if any — is the film on disk.
 *
 * <p>A film offers less to check than a series: there is no season shape, so
 * the whole case rests on the title and the year. That makes the year weigh
 * more here than in {@link SeriesMatcher} — it is the only thing that tells
 * <em>Dune</em> (2021) from <em>Dune</em> (1984) — and it makes the ambiguity
 * rule bite harder: a folder named after a much-remade film, carrying no year
 * anywhere, is left unidentified on purpose.
 *
 * <p>Pure and static like its sibling, and it borrows the sibling's
 * normalisation and similarity so the two judges read titles the same way.
 */
public final class MovieMatcher {

    /** Below this the names simply are not the same title, whatever else agrees. */
    static final double MINIMUM_TITLE_SCORE = 0.65;

    /** The score a match must reach — higher than the series bar, having fewer signals. */
    static final double MINIMUM_SCORE = 0.75;

    /** How clearly the winner must beat the runner-up; ties are remakes. */
    static final double MINIMUM_LEAD = 0.05;

    /** What agreeing — or disagreeing — on the year is worth. */
    static final double YEAR_WEIGHT = 0.15;

    private MovieMatcher() {
    }

    /**
     * The one candidate worth writing to disk, or empty when none is, or when
     * two are too close to call.
     */
    public static Optional<TvdbCandidate> pick(
            MovieEvidence evidence, Optional<TitleGuess> guess, List<TvdbCandidate> candidates) {

        TvdbCandidate best = null;
        double bestScore = 0;
        double secondScore = 0;
        for (TvdbCandidate candidate : candidates) {
            double candidateScore = score(evidence, guess, candidate);
            if (candidateScore > bestScore) {
                secondScore = bestScore;
                bestScore = candidateScore;
                best = candidate;
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
     * The candidate's score: the best the title manages against name and
     * aliases, moved by the year where both sides name one. Not clamped —
     * the number only ranks candidates and clears thresholds, and clamping
     * would flatten the very lead that tells a remake's two years apart.
     */
    static double score(MovieEvidence evidence, Optional<TitleGuess> guess, TvdbCandidate candidate) {
        double title = titleScore(evidence, guess, candidate);
        if (title < MINIMUM_TITLE_SCORE) {
            return 0;
        }
        double score = title;
        Optional<Integer> known = evidence.yearHint().or(() -> guess.flatMap(TitleGuess::year));
        if (known.isPresent() && candidate.year().isPresent()) {
            // Off by one is forgiven: festival premiere and wide release
            // straddle new year often enough that the sources disagree.
            score += Math.abs(known.get() - candidate.year().get()) <= 1
                    ? YEAR_WEIGHT
                    : -YEAR_WEIGHT;
        }
        return score;
    }

    /** The best similarity any of the candidate's names manages against any of ours. */
    static double titleScore(MovieEvidence evidence, Optional<TitleGuess> guess, TvdbCandidate candidate) {
        // The extension is the one part of the file name that is certainly
        // not title; the rest the normalisation and edit distance absorb.
        String fileBaseName = VideoFiles.withoutExtension(evidence.videoFileName());
        double best = 0;
        for (String candidateName : namesOf(candidate)) {
            best = Math.max(best, SeriesMatcher.similarity(candidateName, evidence.folderName()));
            best = Math.max(best, SeriesMatcher.similarity(candidateName, fileBaseName));
            if (guess.isPresent()) {
                best = Math.max(best, SeriesMatcher.similarity(candidateName, guess.get().title()));
            }
        }
        return best;
    }

    private static List<String> namesOf(TvdbCandidate candidate) {
        List<String> names = new ArrayList<>();
        names.add(candidate.name());
        names.addAll(candidate.aliases());
        return names;
    }
}
