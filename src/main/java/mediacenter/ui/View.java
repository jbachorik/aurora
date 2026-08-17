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

    /**
     * Whether this page wants the screen to itself, without the header and the
     * hint bar. A photograph fills the screen; everything else is furniture
     * around it.
     */
    default boolean fullBleed() {
        return false;
    }

    /**
     * Called when this page leaves the stack for good. A page that started a
     * thread or a walk stops it here — nothing else will.
     */
    default void onHidden() {
        // Most pages hold nothing that outlives them.
    }
}
