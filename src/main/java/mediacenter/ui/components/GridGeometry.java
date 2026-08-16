package mediacenter.ui.components;

import java.util.List;

import javafx.geometry.Bounds;

/**
 * Where the arrow keys go, worked out from rectangles alone.
 *
 * <p>Separated from the grid so it can be reasoned about — and tested — without a
 * rendered scene, which is what let the focus lift quietly break row detection:
 * a tile drawn 4% larger has a box that starts higher than its neighbours', and
 * read as position that made every neighbour look like the row below.
 */
final class GridGeometry {

    /** Tiles whose centres are within this many pixels are on the same row. */
    private static final double ROW_EPSILON = 2;

    private GridGeometry() {
    }

    /**
     * Index of the tile directly above or below the one at {@code from}, matching
     * horizontal position as closely as the rows allow.
     *
     * <p>Rows are told apart by the centre of each tile rather than its top edge.
     * The focus lift scales a tile about its own centre, so the centre is the one
     * measurement it leaves alone; the top edge moves, and a neighbour whose top
     * edge is lower reads as a row below that does not exist.
     *
     * @param tiles the bounds of every tile
     * @return {@code -1} when there is no such row
     */
    static int indexInAdjacentRow(List<Bounds> tiles, int from, boolean upwards) {
        if (from < 0 || from >= tiles.size()) {
            return -1;
        }
        Bounds origin = tiles.get(from);
        double originY = origin.getCenterY();
        double originCenterX = origin.getCenterX();

        double targetRowY = Double.NaN;
        for (Bounds tile : tiles) {
            double y = tile.getCenterY();
            boolean candidate = upwards ? y < originY - ROW_EPSILON : y > originY + ROW_EPSILON;
            if (!candidate) {
                continue;
            }
            if (Double.isNaN(targetRowY) || (upwards ? y > targetRowY : y < targetRowY)) {
                targetRowY = y;
            }
        }
        if (Double.isNaN(targetRowY)) {
            return -1;
        }

        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < tiles.size(); i++) {
            Bounds bounds = tiles.get(i);
            if (Math.abs(bounds.getCenterY() - targetRowY) > ROW_EPSILON) {
                continue;
            }
            double distance = Math.abs(bounds.getCenterX() - originCenterX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
