package mediacenter.ui.components;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.control.Label;

/** A large home-screen action: a symbol, a caption and an optional sub-caption. */
public final class ActionTile extends Tile {

    /** The most a tile is ever given; a wide screen stops here rather than sprawling. */
    public static final double WIDTH = 280;

    /**
     * Deliberately shorter than it is wide: the home page has to seat this row, a
     * heading and a full poster row inside 960 points, which is what this machine's
     * screen comes to.
     */
    public static final double HEIGHT = 210;

    /**
     * The least a tile shrinks to. Low enough that six actions still share one row
     * on the narrowest window the application allows, because a home row that wraps
     * traps the Down key inside itself instead of passing it to the recent row.
     */
    public static final double MIN_WIDTH = 120;

    /** Two lines of caption, so the symbols line up across every tile. */
    private static final double TITLE_HEIGHT = 76;

    /** Room for the tile's own border and padding on both sides. */
    private static final double TEXT_INSET = 20;

    private final String title;
    private final List<Label> textLabels = new ArrayList<>();

    public ActionTile(String symbol, String title, String subtitle) {
        this.title = title;

        getStyleClass().add("action-tile");
        setAlignment(Pos.CENTER);
        setSpacing(4);

        Label symbolLabel = new Label(symbol);
        symbolLabel.getStyleClass().add("action-tile-symbol");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("action-tile-title");
        titleLabel.setWrapText(true);
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.setMinHeight(TITLE_HEIGHT);
        titleLabel.setPrefHeight(TITLE_HEIGHT);
        textLabels.add(titleLabel);

        getChildren().addAll(symbolLabel, titleLabel);

        if (subtitle != null && !subtitle.isBlank()) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("action-tile-subtitle");
            subtitleLabel.setWrapText(true);
            subtitleLabel.setAlignment(Pos.CENTER);
            textLabels.add(subtitleLabel);
            getChildren().add(subtitleLabel);
        }

        resizeToWidth(WIDTH);
    }

    /**
     * Height is deliberately left alone: it is set by what the tile holds — a
     * symbol above two lines of caption — while width is only ever a question of
     * how many tiles have to share the row.
     */
    @Override
    public void resizeToWidth(double width) {
        double clamped = Math.max(MIN_WIDTH, Math.min(WIDTH, width));
        setPrefSize(clamped, HEIGHT);
        setMinSize(clamped, HEIGHT);
        setMaxSize(clamped, HEIGHT);
        for (Label label : textLabels) {
            label.setMaxWidth(clamped - TEXT_INSET);
        }
    }

    @Override
    public String title() {
        return title;
    }
}
