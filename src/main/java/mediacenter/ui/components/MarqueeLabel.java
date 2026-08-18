package mediacenter.ui.components;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * A single line of text that scrolls slowly when it does not fit.
 *
 * <p>The label inside is laid out at its full text width and the pane clips it,
 * so nothing is ever ellipsized away. While {@link #setScrolling(boolean)} is
 * off — or the text simply fits — the line sits still showing its beginning;
 * switched on, it glides back and forth at reading speed with a pause at each
 * end, timed by {@link MarqueeGeometry}.
 *
 * <p>Scrolling is meant to be enabled for the one line the viewer is looking
 * at (the selected row): a page of names all sliding at once cannot be read.
 */
public final class MarqueeLabel extends Pane {

    private final Label label = new Label();

    private Timeline marquee;
    private boolean scrolling;

    public MarqueeLabel(String text) {
        label.setText(text);
        // The whole point: lay the text out at full width and let the clip crop
        // it, instead of Label's own truncation replacing the tail with "…".
        label.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().add(label);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        // Both can change what overflows: the pane resizing with the window, and
        // the label resizing with a font or style change.
        widthProperty().addListener((observable, was, is) -> restart());
        label.widthProperty().addListener((observable, was, is) -> restart());
    }

    /** Styles the text; the pane itself stays unstyled so the clip is invisible. */
    public void addTextStyleClass(String styleClass) {
        label.getStyleClass().add(styleClass);
    }

    /** Starts or stops the glide; stopping snaps the line back to its beginning. */
    public void setScrolling(boolean scrolling) {
        if (this.scrolling == scrolling) {
            return;
        }
        this.scrolling = scrolling;
        restart();
    }

    private void restart() {
        if (marquee != null) {
            marquee.stop();
            marquee = null;
        }
        label.setTranslateX(0);
        double overflow = MarqueeGeometry.overflow(label.getWidth(), getWidth());
        if (!scrolling || overflow <= 0 || getWidth() <= 0) {
            return;
        }
        double glide = MarqueeGeometry.glideSeconds(overflow);
        double hold = MarqueeGeometry.HOLD_SECONDS;
        // Linear on the glides: constant speed is what makes the middle of the
        // name as readable as its ends.
        marquee = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(label.translateXProperty(), 0)),
                new KeyFrame(Duration.seconds(hold),
                        new KeyValue(label.translateXProperty(), 0)),
                new KeyFrame(Duration.seconds(hold + glide),
                        new KeyValue(label.translateXProperty(), -overflow, Interpolator.LINEAR)),
                new KeyFrame(Duration.seconds(hold + glide + hold),
                        new KeyValue(label.translateXProperty(), -overflow)),
                new KeyFrame(Duration.seconds(hold + glide + hold + glide),
                        new KeyValue(label.translateXProperty(), 0, Interpolator.LINEAR)));
        marquee.setCycleCount(Animation.INDEFINITE);
        marquee.play();
    }

    @Override
    protected void layoutChildren() {
        label.autosize();
        label.relocate(0, Math.round((getHeight() - label.getHeight()) / 2));
    }

    @Override
    protected double computeMinWidth(double height) {
        // The line must be croppable, not push its row wider than the list.
        return 0;
    }

    @Override
    protected double computePrefHeight(double width) {
        return label.prefHeight(-1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return label.minHeight(-1);
    }
}
