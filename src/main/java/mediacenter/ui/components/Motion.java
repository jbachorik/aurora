package mediacenter.ui.components;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * The application's motion vocabulary, kept in one place so every transition
 * feels like part of the same UI.
 *
 * <p>Deliberately restrained: movements are short and never block input, so
 * holding a direction key still walks the grid at full speed. Nothing here
 * animates content that the viewer is trying to read.
 */
public final class Motion {

    /** Focus feedback. Must be quicker than a comfortable key repeat. */
    public static final Duration QUICK = Duration.millis(130);

    /** Page changes. */
    public static final Duration NORMAL = Duration.millis(190);

    /** Artwork appearing once it has been decoded. */
    public static final Duration GENTLE = Duration.millis(240);

    /** Standard ease-out: fast to start, settles softly. */
    public static final Interpolator EASE = Interpolator.SPLINE(0.22, 0.61, 0.36, 1);

    /** How far a page slides in, in pixels. */
    public static final double PAGE_SLIDE = 34;

    private Motion() {
    }

    /** Fades a node in from fully transparent. */
    public static void fadeIn(Node node, Duration duration) {
        node.setOpacity(0);
        FadeTransition fade = new FadeTransition(duration, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        fade.play();
    }

    /** Holds a caption long enough to be read, then takes it away. */
    public static FadeTransition fadeOutAfter(Node node) {
        FadeTransition fade = new FadeTransition(GENTLE, node);
        fade.setDelay(Duration.seconds(3));
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setInterpolator(EASE);
        fade.setOnFinished(event -> node.setVisible(false));
        return fade;
    }

    /**
     * Slides a node in horizontally while fading it up.
     *
     * @param fromX offset to start at; negative comes from the left
     */
    public static void slideFadeIn(Node node, double fromX, Duration duration) {
        node.setOpacity(0);
        node.setTranslateX(fromX);

        FadeTransition fade = new FadeTransition(duration, node);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(duration, node);
        slide.setFromX(fromX);
        slide.setToX(0);

        ParallelTransition entrance = new ParallelTransition(fade, slide);
        entrance.setInterpolator(EASE);
        entrance.play();
    }
}
