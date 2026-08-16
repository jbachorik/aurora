package mediacenter.ui;

import javafx.scene.Node;

/** One full-screen page of the media center. */
public interface View {

    /** The page content. */
    Node node();

    /** Heading shown in the shell header. */
    String title();

    /** Optional second line, e.g. the folder being browsed. */
    default String subtitle() {
        return "";
    }

    /** Puts the keyboard focus back on the current selection. */
    void focusSelection();

    /** Re-reads whatever the page shows (F5). */
    default void refresh() {
        // Nothing to reload by default.
    }

    /** Called every time the page becomes visible. */
    default void onShown() {
        focusSelection();
    }
}
