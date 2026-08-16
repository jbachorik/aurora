package mediacenter.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;

import mediacenter.media.MediaRoot;
import mediacenter.ui.components.ActionTile;
import mediacenter.ui.components.Tile;
import mediacenter.ui.components.TileGrid;

/** Chooser shown when a home action maps to more than one configured folder. */
public final class RootsView implements View {

    private final Navigation navigation;
    private final String title;
    private final List<MediaRoot> roots;
    private final TileGrid grid = new TileGrid();

    public RootsView(Navigation navigation, String title, List<MediaRoot> roots) {
        this.navigation = navigation;
        this.title = title;
        this.roots = List.copyOf(roots);

        grid.setOnActivate(this::activate);
        if (this.roots.isEmpty()) {
            grid.showMessage("No folders are configured yet.\nOpen Settings to add one.");
        } else {
            List<Tile> tiles = new ArrayList<>(this.roots.size());
            for (MediaRoot root : this.roots) {
                tiles.add(new ActionTile("▤", root.displayName(), root.displayPath()));
            }
            grid.setTiles(tiles);
        }
    }

    @Override
    public Node node() {
        return grid;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public void focusSelection() {
        grid.focusSelection();
    }

    private void activate(Tile tile) {
        int index = grid.selectedIndex();
        if (index >= 0 && index < roots.size()) {
            MediaRoot root = roots.get(index);
            navigation.browse(root, root.path());
        }
    }
}
