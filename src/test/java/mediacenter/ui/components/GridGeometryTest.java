package mediacenter.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GridGeometryTest {

    private static final double WIDTH = 280;
    private static final double HEIGHT = 210;
    private static final double GAP = 24;

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
