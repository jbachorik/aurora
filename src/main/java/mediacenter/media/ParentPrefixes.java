package mediacenter.media;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Dropping a parent folder's name from the front of an entry's own name.
 *
 * <p>Well-organised media repeats itself: {@code Show Name/Show Name -
 * S01E01.mkv}, or a film folder holding a file that spells the folder again in
 * dots. The folder's name is already on screen — it is the page being browsed —
 * so the echo at the front of every line only pushes the part that differs out
 * of view.
 *
 * <p>Matching is by word, not by character: both names are read as their runs
 * of letters and digits, so {@code The Shawshank Redemption (1994)} still
 * claims the front of {@code The.Shawshank.Redemption.1994.1080p.mkv} —
 * separators and bracketing differ, the words do not. Whole words only:
 * {@code The Matrix} never bites into {@code The Matrixx}. Whatever separators
 * the echo leaves behind at the front are trimmed with it.
 *
 * <p>Deliberately all-or-nothing per folder name, and never destructive: a
 * name the rule would reduce to nothing — or to nothing but its extension — is
 * shown whole, because "{@code mkv}" on a line helps nobody.
 */
public final class ParentPrefixes {

    private ParentPrefixes() {
    }

    /**
     * The name with the longest matching parent-folder echo removed and the
     * result trimmed, or the name untouched when no parent matches — or when
     * removing the echo would leave nothing worth reading.
     *
     * @param name              an entry's name exactly as it is on disk
     * @param parentFolderNames the on-disk names of the folders above the entry
     */
    public static String withoutParentPrefix(String name, Collection<String> parentFolderNames) {
        List<Token> tokens = tokenize(name);
        int prefixEnd = 0;
        for (String parentName : parentFolderNames) {
            prefixEnd = Math.max(prefixEnd, matchedPrefixEnd(tokens, tokenize(parentName)));
        }
        if (prefixEnd <= 0) {
            return name;
        }
        String remainder = trimLeadingSeparators(name.substring(prefixEnd));
        return carriesAName(remainder, name) ? remainder : name;
    }

    /**
     * Where in the raw name the echo of this parent ends, or zero when the
     * parent's words are not the first words of the name.
     */
    private static int matchedPrefixEnd(List<Token> tokens, List<Token> parentTokens) {
        if (parentTokens.isEmpty() || tokens.size() < parentTokens.size()) {
            return 0;
        }
        for (int i = 0; i < parentTokens.size(); i++) {
            if (!tokens.get(i).text().equals(parentTokens.get(i).text())) {
                return 0;
            }
        }
        return tokens.get(parentTokens.size() - 1).end();
    }

    /** True when something besides separators and the extension survived. */
    private static boolean carriesAName(String remainder, String originalName) {
        if (remainder.isEmpty()) {
            return false;
        }
        int dot = originalName.lastIndexOf('.');
        String extension = dot > 0 ? originalName.substring(dot + 1) : "";
        return !remainder.equalsIgnoreCase(extension);
    }

    /** The separators an excised echo leaves stranded at the front of a line. */
    private static String trimLeadingSeparators(String text) {
        int start = 0;
        while (start < text.length() && isSeparator(text.charAt(start))) {
            start++;
        }
        return text.substring(start).strip();
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '.' || c == '-' || c == '_';
    }

    /** A run of letters and digits, and where its last character sits in the raw name. */
    private record Token(String text, int end) {
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (Character.isLetterOrDigit(text.charAt(i))) {
                int start = i;
                while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(text.substring(start, i).toLowerCase(Locale.ROOT), i));
            } else {
                i++;
            }
        }
        return tokens;
    }
}
