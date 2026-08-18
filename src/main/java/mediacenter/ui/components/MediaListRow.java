package mediacenter.ui.components;

import java.util.Collection;
import java.util.Optional;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import mediacenter.media.MediaItem;
import mediacenter.media.ParentPrefixes;

/**
 * One line of a browsed folder: a type symbol and the actual file name.
 *
 * <p>Deliberately not a tile. The whole point of the list is that the name on
 * disk is readable — the row shows it verbatim, extension and all, and a name
 * still wider than the line scrolls slowly under the selection rather than
 * being cut off. Only the selected row scrolls; the rest hold still, because a
 * page of sliding names cannot be read. The one edit made is dropping a parent
 * folder's name echoed at the front — see {@link ParentPrefixes} — which is
 * removing repetition, not information: the folder is the page being browsed.
 *
 * <p>Focus is the selection, exactly as with {@code Tile}: the {@code :focused}
 * pseudo-class drives the highlight, so "what is highlighted" and "what Enter
 * opens" can never disagree.
 */
public final class MediaListRow extends HBox {

    /** Wide enough that every symbol lines the names up in one column. */
    private static final double SYMBOL_WIDTH = 52;

    private final MediaItem item;
    private final String title;
    private final MarqueeLabel name;

    /**
     * A row for one scanned entry, showing its on-disk name — less any parent
     * folder's name echoed at the front of it.
     *
     * @param parentFolderNames the on-disk names of the folders above the entry,
     *                          within the root being browsed
     */
    public static MediaListRow forItem(MediaItem item, Collection<String> parentFolderNames) {
        return new MediaListRow(item, symbolFor(item),
                ParentPrefixes.withoutParentPrefix(fileNameOf(item), parentFolderNames));
    }

    /** A row for a page action such as the slideshow; {@link #item()} is empty. */
    public static MediaListRow action(String symbol, String title) {
        return new MediaListRow(null, symbol, title);
    }

    private MediaListRow(MediaItem item, String symbol, String text) {
        this.item = item;
        this.title = text;

        getStyleClass().add("media-row");
        setFocusTraversable(true);
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(12);

        Label symbolLabel = new Label(symbol);
        symbolLabel.getStyleClass().add("media-row-symbol");
        symbolLabel.setMinWidth(SYMBOL_WIDTH);
        symbolLabel.setPrefWidth(SYMBOL_WIDTH);
        symbolLabel.setAlignment(Pos.CENTER);

        name = new MarqueeLabel(text);
        name.addTextStyleClass("media-row-name");
        HBox.setHgrow(name, Priority.ALWAYS);

        getChildren().addAll(symbolLabel, name);

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

    private static String symbolFor(MediaItem item) {
        return switch (item.type()) {
            case DIRECTORY -> "▤";
            case IMAGE -> "▣";
            case VIDEO -> "▶";
        };
    }

    /**
     * The name as it is on disk, extension included — what this list exists to
     * show. Only a filesystem root has no file name of its own, and such a path
     * never appears as an entry inside a scanned folder; the display name is a
     * fallback for exactly that never-case.
     */
    private static String fileNameOf(MediaItem item) {
        var fileName = item.path().getFileName();
        return fileName == null ? item.displayName() : fileName.toString();
    }
}
