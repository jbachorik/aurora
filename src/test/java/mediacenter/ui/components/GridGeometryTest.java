package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GridGeometryTest {

    private static final double WIDTH = 280;
    private static final double HEIGHT = 210;
    private static final double GAP = 24;

    /** What the grid calls the same row; see {@code GridGeometry.ROW_EPSILON}. */
    private static final double ROW_TOLERANCE = 2;

    /** A row of equally sized tiles laid out left to right. */
    private static List<Bounds> row(double y, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> (Bounds) new BoundingBox(i * (WIDTH + GAP), y, WIDTH, HEIGHT))
                .toList();
    }

    @Test
    @DisplayName("a single row has nothing below it, so the view can be told to move on")
    void reportsNoRowBelowASingleRow() {
        assertEquals(-1, GridGeometry.indexInAdjacentRow(row(0, 5), 0, false));
    }

    @Test
    @DisplayName("the focus lift does not turn a neighbour into a row of its own")
    void ignoresTheFocusLift() {
        // The focused tile is drawn 4% larger, which grows its box in every
        // direction. Read as position, that puts its neighbours lower down.
        List<Bounds> tiles = new java.util.ArrayList<>(row(0, 5));
        double lift = HEIGHT * 0.04 / 2;
        tiles.set(0, new BoundingBox(-WIDTH * 0.04 / 2, -lift, WIDTH * 1.04, HEIGHT * 1.04));

        assertEquals(-1, GridGeometry.indexInAdjacentRow(tiles, 0, false));
        assertEquals(-1, GridGeometry.indexInAdjacentRow(tiles, 0, true));
    }

    @Test
    @DisplayName("a tile is placed where the layout put it, not where its glow reaches")
    void readsPositionFromTheLayoutRatherThanTheDrawnBox() {
        Region tile = new Region();
        tile.resizeRelocate(100, 200, WIDTH, HEIGHT);
        // The focus glow: a drop shadow that hangs further below the tile than
        // the resting one does.
        tile.setEffect(new DropShadow(26, 0, 8, Color.ORANGE));

        Bounds position = GridGeometry.positionOf(tile);

        assertEquals(100, position.getMinX(), 0.001);
        assertEquals(200, position.getMinY(), 0.001);
        assertEquals(WIDTH, position.getWidth(), 0.001);
        assertEquals(HEIGHT, position.getHeight(), 0.001);
        // The box it draws really does sit lower — which is the whole problem.
        assertTrue(tile.getBoundsInParent().getCenterY() > position.getCenterY() + ROW_TOLERANCE,
                "the drawn box should be the misleading one");
    }

    @Test
    @DisplayName("neither does the focus lift move it")
    void ignoresTheScaleOfTheFocusLift() {
        Region tile = new Region();
        tile.resizeRelocate(100, 200, WIDTH, HEIGHT);
        tile.setScaleX(1.04);
        tile.setScaleY(1.04);

        assertEquals(200 + HEIGHT / 2, GridGeometry.positionOf(tile).getCenterY(), 0.001);
    }

    @Test
    @DisplayName("the tile below is the one whose centre is nearest")
    void findsTheNearestTileInTheRowBelow() {
        List<Bounds> tiles = new java.util.ArrayList<>(row(0, 3));
        tiles.addAll(row(HEIGHT + GAP, 3));

        assertEquals(3, GridGeometry.indexInAdjacentRow(tiles, 0, false));
        assertEquals(5, GridGeometry.indexInAdjacentRow(tiles, 2, false));
        assertEquals(1, GridGeometry.indexInAdjacentRow(tiles, 4, true));
    }

    @Test
    @DisplayName("the bottom row has nothing below it")
    void reportsNoRowBelowTheLastRow() {
        List<Bounds> tiles = new java.util.ArrayList<>(row(0, 3));
        tiles.addAll(row(HEIGHT + GAP, 3));

        assertEquals(-1, GridGeometry.indexInAdjacentRow(tiles, 4, false));
    }
}
