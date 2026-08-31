package mediacenter.ui.components;

import java.util.Collection;
import java.util.Optional;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import mediacenter.media.DisplayNames;
import mediacenter.media.MediaItem;
import mediacenter.media.MediaItemType;
import mediacenter.media.ParentPrefixes;

/**
 * One line of a browsed folder: a type symbol and the entry's readable name.
 *
 * <p>Deliberately not a tile. The line carries the {@link DisplayNames
 * display name} — extension gone, separators spaced, ordering prefixes and
 * leading episode tags dropped — because from a sofa the name is a title, not
 * a path; the on-disk truth stays one glance away in the header's subtitle,
 * and the on-disk name still governs the sort. A name wider than the line
 * scrolls slowly under the selection rather than being cut off; only the
 * selected row scrolls, because a page of sliding names cannot be read. A
 * parent folder's name echoed at the front is dropped too — see
 * {@link ParentPrefixes} — and where that uncovers an episode tag, the tag
 * goes the way a leading one always goes.
 *
 * <p>Focus is the selection, exactly as with {@code Tile}: the {@code :focused}
 * pseudo-class drives the highlight, so "what is highlighted" and "what Enter
 * opens" can never disagree.
 */
public final class MediaListRow extends HBox {

    /** Wide enough that every symbol lines the names up in one column. */
    private static final double SYMBOL_WIDTH = 52;

    /** The style class carrying the dimmed already-seen look. */
    private static final String WATCHED_CLASS = "media-row-watched";

    private final MediaItem item;
    private String title;
    private final Label symbol;
    private final MarqueeLabel name;
    /** What the symbol column shows while the row is not marked watched. */
    private MediaItemType symbolType;
    private boolean watched;

    /**
     * A row for one scanned entry, captioned with its display name — less any
     * parent folder's name echoed at the front of it.
     *
     * @param parentFolderNames the on-disk names of the folders above the entry,
     *                          within the root being browsed
     */
    public static MediaListRow forItem(MediaItem item, Collection<String> parentFolderNames) {
        return new MediaListRow(item, symbolFor(item), captionFor(item, parentFolderNames));
    }

    /**
     * The line's caption: the display name, the parent echo gone, and any
     * episode tag the echo's removal uncovered at the front gone with it —
     * "Breaking Bad S01E01 Pilot" inside "Breaking Bad" reads simply "Pilot".
     * Static and pure so the whole pipeline stays testable without a toolkit.
     */
    static String captionFor(MediaItem item, Collection<String> parentFolderNames) {
        String withoutEcho = ParentPrefixes.withoutParentPrefix(item.displayName(), parentFolderNames);
        return DisplayNames.withoutLeadingEpisodeTag(withoutEcho);
    }

    /** A row for a page action such as the slideshow; {@link #item()} is empty. */
    public static MediaListRow action(String symbol, String title) {
        return new MediaListRow(null, symbol, title);
    }

    private MediaListRow(MediaItem item, String symbol, String text) {
        this.item = item;
        this.title = text;
        this.symbolType = item == null ? null : item.type();

        getStyleClass().add("media-row");
        setFocusTraversable(true);
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        this.symbol = new Label(symbol);
        this.symbol.getStyleClass().add("media-row-symbol");
        this.symbol.setMinWidth(SYMBOL_WIDTH);
        this.symbol.setPrefWidth(SYMBOL_WIDTH);
        this.symbol.setAlignment(Pos.CENTER);

        name = new MarqueeLabel(text);
        name.addTextStyleClass("media-row-name");
        HBox.setHgrow(name, Priority.ALWAYS);

        getChildren().addAll(this.symbol, name);

        // The marquee is for the line being looked at; everyone else holds still.
        focusedProperty().addListener((observable, wasFocused, isFocused) ->
                name.setScrolling(isFocused));
    }

    /** The entry this row stands for; empty for an action row. */
    public Optional<MediaItem> item() {
        return Optional.ofNullable(item);
    }

    /** The text on the line, for anything announcing the selection. */
    public String title() {
        return title;
    }

    /**
     * Re-badges a folder row as the one medium it turned out to hold, so the
     * line tells the truth about what Enter will do: play, not drill down. The
     * name stays the folder's own — that is still what sits on the disk.
     */
    public void showMediaSymbol(MediaItemType type) {
        symbolType = type;
        updateSymbol();
    }

    /**
     * Re-captions the row with what the folder turned out to <em>be</em> — the
     * scraped title of an identified series or film. The one deliberate
     * departure from showing the on-disk name: "Breaking Bad" is the answer
     * the ripper's name was a question about, and the disk name still governs
     * the sort, so the shelf keeps its order.
     */
    public void showTitle(String newTitle) {
        title = newTitle;
        name.setText(newTitle);
    }

    /**
     * Shows or removes the watched mark: a check in the symbol column and a
     * dimmed name, so an unseen title stands out on a shelf of seen ones. Only
     * ever called for rows that Enter would play as a video.
     */
    public void showWatched(boolean watched) {
        this.watched = watched;
        if (watched && !getStyleClass().contains(WATCHED_CLASS)) {
            getStyleClass().add(WATCHED_CLASS);
        } else if (!watched) {
            getStyleClass().remove(WATCHED_CLASS);
        }
        updateSymbol();
    }

    private void updateSymbol() {
        symbol.setText(watched ? "✓" : symbolFor(symbolType));
    }

    private static String symbolFor(MediaItem item) {
        return symbolFor(item.type());
    }

    private static String symbolFor(MediaItemType type) {
        return switch (type) {
            case DIRECTORY -> "▤";
            case IMAGE -> "▣";
            case VIDEO -> "▶";
        };
    }

}
