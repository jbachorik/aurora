package mediacenter.ui.components;

import javafx.geometry.Pos;
import javafx.scene.control.Label;

/** A large home-screen action: a symbol, a caption and an optional sub-caption. */
public final class ActionTile extends Tile {

    public static final double WIDTH = 280;
    public static final double HEIGHT = 260;

    /** Two lines of caption, so the symbols line up across every tile. */
    private static final double TITLE_HEIGHT = 76;

    private final String title;

    public ActionTile(String symbol, String title, String subtitle) {
        this.title = title;

        getStyleClass().add("action-tile");
        setPrefSize(WIDTH, HEIGHT);
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setAlignment(Pos.CENTER);
        setSpacing(4);

        Label symbolLabel = new Label(symbol);
        symbolLabel.getStyleClass().add("action-tile-symbol");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("action-tile-title");
        titleLabel.setWrapText(true);
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.setMaxWidth(WIDTH - 20);
        titleLabel.setMinHeight(TITLE_HEIGHT);
        titleLabel.setPrefHeight(TITLE_HEIGHT);

        getChildren().addAll(symbolLabel, titleLabel);

        if (subtitle != null && !subtitle.isBlank()) {
            Label subtitleLabel = new Label(subtitle);
            subtitleLabel.getStyleClass().add("action-tile-subtitle");
            subtitleLabel.setWrapText(true);
            subtitleLabel.setMaxWidth(WIDTH - 20);
            subtitleLabel.setAlignment(Pos.CENTER);
            getChildren().add(subtitleLabel);
        }
    }

    @Override
    public String title() {
        return title;
    }
}
