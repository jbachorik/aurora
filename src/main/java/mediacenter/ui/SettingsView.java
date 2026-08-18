package mediacenter.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import mediacenter.config.ApplicationSettings;
import mediacenter.config.Theme;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.platform.PlatformServices;
import mediacenter.ui.components.ScrollGeometry;

/**
 * Settings kept deliberately flat: one screen, large rows, no nested pages.
 */
public final class SettingsView implements View {

    private static final double BUTTON_MIN_WIDTH = 160;

    /** Breathing room left above and below a control scrolled into view. */
    private static final double SCROLL_MARGIN = 24;

    private final UiContext context;
    private final PlatformServices platform;
    private final Consumer<ApplicationSettings> onSettingsChanged;

    private final VBox root = new VBox();
    private final ScrollPane scroller = new ScrollPane(root);
    private final Label vlcValue = new Label();
    private final Label vlcStatus = new Label();
    private final Label browserValue = new Label();
    private final Label browserStatus = new Label();
    private final ToggleButton fullScreenToggle = new ToggleButton();
    private final ToggleGroup themeGroup = new ToggleGroup();
    private final Label slideshowValue = new Label();
    private final List<ToggleButton> slideshowToggles = new ArrayList<>();
    private final Label bufferValue = new Label();
    private final List<ToggleButton> bufferToggles = new ArrayList<>();
    private final ListView<MediaRoot> rootsList = new ListView<>();
    private final Label rootStatus = new Label();

    /**
     * The controls the arrow keys walk, grouped into rows in screen order. Every
     * row on this screen is reachable with the arrows alone; nothing here may
     * require Tab.
     */
    private final List<List<Node>> navigationRows = new ArrayList<>();

    /** Where the next media-folder picker should open, so browsing does not restart from scratch. */
    private File lastBrowsedDirectory;

    /** The same for programs, which are kept apart: they live nowhere near the media. */
    private File lastBrowsedProgramDirectory;

    /** Where the keyboard was, for when a dialog takes the focus away and gives it back elsewhere. */
    private Position lastPosition;

    public SettingsView(UiContext context, PlatformServices platform, Consumer<ApplicationSettings> onSettingsChanged) {
        this.context = context;
        this.platform = platform;
        this.onSettingsChanged = onSettingsChanged;

        root.getStyleClass().add("settings-view");
        root.setSpacing(18);
        root.setPadding(new Insets(24, 32, 24, 32));

        // The page is taller than a small screen, and a bare VBox handed to the
        // shell is laid out at *at least* its minimum height: the surplus simply
        // hangs off the bottom of the window, taking the media folders and the
        // hint bar with it, and no amount of pressing Down brings it back.
        scroller.getStyleClass().add("settings-scroll");
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Without this the viewport inherits the content's minimum height and the
        // pane grows past the window instead of scrolling inside it.
        scroller.setMinHeight(0);

        // "vlc.exe" means nothing on a Mac, where the same thing is an application
        // bundle, so every program label is worded by the platform.
        String programNoun = platform.executableNoun();

        Button chooseVlc = new Button("Choose VLC " + programNoun + "…");
        chooseVlc.setOnAction(event -> chooseVlcExecutable());

        Button detectVlc = new Button("Detect");
        detectVlc.setOnAction(event -> detectVlc());

        Button chooseBrowser = new Button("Choose browser " + programNoun + "…");
        chooseBrowser.setOnAction(event -> chooseBrowser());
        Button clearBrowser = new Button("Clear");
        clearBrowser.setOnAction(event -> {
            update(settings().withBrowserPath(Optional.empty()));
            showStatus(browserStatus, null);
        });

        fullScreenToggle.setOnAction(event ->
                update(settings().withFullScreen(fullScreenToggle.isSelected())));

        Node vlcRow = settingRow("VLC player", vlcValue, vlcStatus, chooseVlc, detectVlc);
        navigationRows.add(List.of(chooseVlc, detectVlc));

        Node browserRow = settingRow("Browser (optional)", browserValue, browserStatus,
                chooseBrowser, clearBrowser);
        navigationRows.add(List.of(chooseBrowser, clearBrowser));

        Node fullScreenRow = settingRow("Full screen", new Label(), null, fullScreenToggle);
        navigationRows.add(List.of(fullScreenToggle));

        // The order these are built in is the order the arrow keys walk: each
        // appends its own row to navigationRows as it goes.
        Node themeRow = themeRow();
        Node slideshowRow = slideshowRow();
        Node bufferRow = bufferRow();
        Node rootsSection = mediaRootsSection();

        root.getChildren().addAll(
                vlcRow, browserRow, fullScreenRow, themeRow, slideshowRow, bufferRow, rootsSection);

        installArrowNavigation();
        readSettings();
    }

    @Override
    public Node node() {
        return scroller;
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void focusSelection() {
        focus(0, 0);
    }

    @Override
    public void onShown() {
        readSettings();
        focusSelection();
    }

    @Override
    public void refresh() {
        readSettings();
    }

    // -- layout -------------------------------------------------------------

    /**
     * One card: the name, the current value, the controls, and — underneath and
     * inside the same card — whatever the last action on this row had to say.
     * Feedback belongs next to the button that caused it; a message parked at the
     * bottom of the screen reads as nothing having happened at all.
     */
    private Node settingRow(String label, Label value, Label status, Region... controls) {
        Label name = new Label(label);
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        // The value is the only part allowed to shrink: a long UNC path must
        // ellipsize rather than squeeze the buttons until their labels are cut.
        value.getStyleClass().add("setting-value");
        HBox.setHgrow(value, Priority.ALWAYS);
        value.setMaxWidth(Double.MAX_VALUE);
        value.setMinWidth(80);

        HBox line = new HBox(16, name, value);
        line.setAlignment(Pos.CENTER_LEFT);
        for (Region control : controls) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
        line.getChildren().addAll(controls);

        VBox card = new VBox(8, line);
        card.getStyleClass().add("setting-row");
        if (status != null) {
            card.getChildren().add(prepareStatus(status));
        }
        return card;
    }

    private Label prepareStatus(Label status) {
        status.getStyleClass().add("setting-status");
        status.setWrapText(true);
        showStatus(status, null);
        return status;
    }

    /** Shows a message, or takes the label out of the layout when there is none. */
    private static void showStatus(Label status, String message) {
        boolean present = message != null && !message.isBlank();
        status.setText(present ? message : "");
        status.setVisible(present);
        status.setManaged(present);
    }

    /**
     * Dark suits a dim living room and the black fullscreen player; light suits a
     * bright one. One flat row, no nested page.
     */
    private Node themeRow() {
        Label name = new Label("Theme");
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        Label value = new Label();
        value.getStyleClass().add("setting-value");
        HBox.setHgrow(value, Priority.ALWAYS);
        value.setMaxWidth(Double.MAX_VALUE);

        List<Node> toggles = new ArrayList<>();
        HBox choices = new HBox(12);
        choices.setAlignment(Pos.CENTER_RIGHT);
        for (Theme theme : Theme.values()) {
            ToggleButton button = new ToggleButton(theme.displayName());
            button.setUserData(theme);
            button.setToggleGroup(themeGroup);
            button.setMinWidth(Region.USE_PREF_SIZE);
            // A toggle group lets a second click clear the selection; the theme
            // must always be one of the two.
            button.setOnAction(event -> {
                button.setSelected(true);
                update(settings().withTheme(theme));
            });
            choices.getChildren().add(button);
            toggles.add(button);
        }
        navigationRows.add(List.copyOf(toggles));

        HBox line = new HBox(16, name, value, choices);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(line);
        card.getStyleClass().add("setting-row");
        return card;
    }

    /**
     * How long each photograph stays. Two toggles rather than a number field:
     * from the far side of a room there is nothing to type with.
     */
    /**
     * How long the player reads ahead before it starts.
     *
     * <p>Offered because a share reached over a slow or distant link delivers in
     * fits: the average may be far above what the video needs while individual
     * reads stall, and playback judders on the gaps rather than on any shortage.
     * Reading further ahead covers them. It cannot help when the link is simply
     * slower than the video's bitrate — that is a wait no buffer shortens.
     */
    private Node bufferRow() {
        Label name = new Label("Playback buffer");
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        bufferValue.getStyleClass().add("setting-value");
        HBox.setHgrow(bufferValue, Priority.ALWAYS);
        bufferValue.setMaxWidth(Double.MAX_VALUE);

        ToggleGroup group = new ToggleGroup();
        HBox choices = new HBox(12);
        choices.setAlignment(Pos.CENTER_RIGHT);
        // One second is what VLC does unasked, so the first choice is "leave it
        // alone" under a name that means something from a sofa.
        for (int seconds : List.of(1, 5, 10)) {
            ToggleButton button = new ToggleButton(seconds + "s");
            button.setUserData(seconds);
            button.setToggleGroup(group);
            button.setMinWidth(Region.USE_PREF_SIZE);
            button.setOnAction(event -> {
                button.setSelected(true);
                update(settings().withPlayerBufferSeconds(seconds));
            });
            choices.getChildren().add(button);
            bufferToggles.add(button);
        }
        navigationRows.add(List.copyOf(bufferToggles));

        HBox line = new HBox(16, name, bufferValue, choices);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(line);
        card.getStyleClass().add("setting-row");
        return card;
    }

    private Node slideshowRow() {
        Label name = new Label("Slideshow");
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        slideshowValue.getStyleClass().add("setting-value");
        HBox.setHgrow(slideshowValue, Priority.ALWAYS);
        slideshowValue.setMaxWidth(Double.MAX_VALUE);

        ToggleGroup group = new ToggleGroup();
        HBox choices = new HBox(12);
        choices.setAlignment(Pos.CENTER_RIGHT);
        for (int seconds : List.of(5, 10)) {
            ToggleButton button = new ToggleButton(seconds + "s");
            button.setUserData(seconds);
            button.setToggleGroup(group);
            button.setMinWidth(Region.USE_PREF_SIZE);
            // A toggle group lets a second click clear the selection, which would
            // leave the row showing no interval at all.
            button.setOnAction(event -> {
                button.setSelected(true);
                update(settings().withSlideshowSeconds(seconds));
            });
            choices.getChildren().add(button);
            slideshowToggles.add(button);
        }
        navigationRows.add(List.copyOf(slideshowToggles));

        HBox line = new HBox(16, name, slideshowValue, choices);
        line.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(line);
        card.getStyleClass().add("setting-row");
        return card;
    }

    private Node mediaRootsSection() {
        Label heading = new Label("Media folders");
        heading.getStyleClass().add("section-heading");

        rootsList.getStyleClass().add("roots-list");
        rootsList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(MediaRoot item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.displayName() + "   ·   " + item.type().displayName()
                            + "\n" + item.displayPath());
                }
            }
        });
        VBox.setVgrow(rootsList, Priority.ALWAYS);
        navigationRows.add(List.of(rootsList));

        Button add = new Button("Add");
        add.setOnAction(event -> addRoot());
        Button edit = new Button("Edit");
        edit.setOnAction(event -> editSelectedRoot());
        Button remove = new Button("Remove");
        remove.setOnAction(event -> removeSelectedRoot());
        Button test = new Button("Test");
        test.setOnAction(event -> testSelectedRoot());

        // A plain row, not a ButtonBar: the arrow keys walk these like every other
        // row on the screen, and no platform gets to reorder them behind our back.
        HBox buttons = new HBox(12);
        for (Button button : List.of(add, edit, remove, test)) {
            button.setMinWidth(BUTTON_MIN_WIDTH);
            buttons.getChildren().add(button);
        }
        navigationRows.add(List.copyOf(buttons.getChildren()));

        prepareStatus(rootStatus);

        VBox section = new VBox(12, heading, rootsList, buttons, rootStatus);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    // -- keyboard navigation -------------------------------------------------

    /**
     * Up and down step between rows, left and right between the controls in a row.
     *
     * <p>An event filter, not a handler: the folder list would otherwise swallow
     * the arrow keys for its own selection and the buttons underneath it could
     * only be reached with Tab — which is exactly what a remote control does not
     * have.
     */
    private void installArrowNavigation() {
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case UP -> {
                    moveVertically(-1);
                    event.consume();
                }
                case DOWN -> {
                    moveVertically(1);
                    event.consume();
                }
                case LEFT -> {
                    moveHorizontally(-1);
                    event.consume();
                }
                case RIGHT -> {
                    moveHorizontally(1);
                    event.consume();
                }
                default -> { }
            }
        });
    }

    /** Which control the keyboard is on, as a row and a column. */
    private record Position(int row, int column) { }

    private void moveVertically(int delta) {
        Optional<Position> position = focusedPosition();
        if (position.isEmpty()) {
            resumeWhereFocusWasLost();
            return;
        }
        Position current = position.get();
        // The folder list is walked through before it is left, so a remote can
        // still pick a folder with the same two keys.
        if (navigationRows.get(current.row()).get(current.column()) == rootsList
                && moveWithinRootsList(delta)) {
            return;
        }
        int targetRow = current.row() + delta;
        if (targetRow < 0 || targetRow >= navigationRows.size()) {
            return;
        }
        focus(targetRow, current.column());
        // Arriving at the folder list starts at the end the move came from. Its
        // selection is otherwise wherever a previous visit or a settings reload
        // left it, and a selection already at the far end reads as "no room left"
        // — the list would be stepped straight over instead of walked.
        if (navigationRows.get(targetRow).contains(rootsList)) {
            selectEdgeOfRootsList(delta);
        }
    }

    private void moveHorizontally(int delta) {
        Optional<Position> position = focusedPosition();
        if (position.isEmpty()) {
            resumeWhereFocusWasLost();
            return;
        }
        Position current = position.get();
        int targetColumn = current.column() + delta;
        if (targetColumn >= 0 && targetColumn < navigationRows.get(current.row()).size()) {
            focus(current.row(), targetColumn);
        }
    }

    /**
     * Puts the keyboard back where it was rather than at the top of the screen.
     *
     * <p>A modal dialog leaves the focus owner outside this screen's controls when
     * it closes, and every arrow key is consumed here, so without this the first
     * press after adding a folder would jump to the VLC button at the top — a long
     * way from the folder buttons the viewer was using.
     */
    private void resumeWhereFocusWasLost() {
        Position resume = lastPosition == null ? new Position(0, 0) : lastPosition;
        focus(resume.row(), resume.column());
    }

    /** The end of the folder list a vertical move should arrive at. */
    private void selectEdgeOfRootsList(int delta) {
        int size = rootsList.getItems().size();
        if (size == 0) {
            return;
        }
        int edge = delta > 0 ? 0 : size - 1;
        rootsList.getSelectionModel().select(edge);
        rootsList.scrollTo(edge);
    }

    private boolean moveWithinRootsList(int delta) {
        int size = rootsList.getItems().size();
        if (size == 0) {
            return false;
        }
        int target = rootsList.getSelectionModel().getSelectedIndex() + delta;
        if (target < 0 || target >= size) {
            return false;
        }
        rootsList.getSelectionModel().select(target);
        rootsList.scrollTo(target);
        return true;
    }

    private Optional<Position> focusedPosition() {
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        for (int row = 0; row < navigationRows.size(); row++) {
            List<Node> controls = navigationRows.get(row);
            for (int column = 0; column < controls.size(); column++) {
                if (isSelfOrAncestor(controls.get(column), focused)) {
                    return Optional.of(new Position(row, column));
                }
            }
        }
        return Optional.empty();
    }

    /** Focuses a control, clamping the column to what the target row actually has. */
    private void focus(int row, int column) {
        if (navigationRows.isEmpty()) {
            return;
        }
        List<Node> controls = navigationRows.get(row);
        int clampedColumn = Math.clamp(column, 0, controls.size() - 1);
        Node target = controls.get(clampedColumn);
        target.requestFocus();
        ensureVisible(target);
        lastPosition = new Position(row, clampedColumn);
        if (target == rootsList
                && rootsList.getSelectionModel().isEmpty()
                && !rootsList.getItems().isEmpty()) {
            rootsList.getSelectionModel().selectFirst();
        }
    }

    /**
     * Scrolls a freshly focused control into the viewport.
     *
     * <p>The arrow keys are taken by the filter above, which stops the scroll
     * pane's own skin from ever following the focus. Without this the focus does
     * move onto a control below the fold and nothing appears to happen, because
     * the control it moved to is painted past the bottom of the window.
     */
    private void ensureVisible(Node target) {
        Bounds viewport = scroller.getViewportBounds();
        if (viewport == null) {
            return;
        }
        Bounds inContent = root.sceneToLocal(target.localToScene(target.getBoundsInLocal()));
        if (inContent == null) {
            return;
        }
        scroller.setVvalue(ScrollGeometry.vvalueFor(
                scroller.getVvalue(),
                root.getLayoutBounds().getHeight(),
                viewport.getHeight(),
                inContent.getMinY(),
                inContent.getMaxY(),
                SCROLL_MARGIN));
    }

    private static boolean isSelfOrAncestor(Node candidate, Node focused) {
        for (Node node = focused; node != null; node = node.getParent()) {
            if (node == candidate) {
                return true;
            }
        }
        return false;
    }

    // -- state --------------------------------------------------------------

    private ApplicationSettings settings() {
        return context.settings().get();
    }

    private void readSettings() {
        ApplicationSettings settings = settings();
        // The name of the application, not the path to the binary inside it: what is
        // stored has to be runnable, which on macOS is buried several directories
        // down and reads as noise from the other side of a room.
        vlcValue.setText(settings.vlcPath().map(platform::programLabel).orElse("Not configured"));
        browserValue.setText(settings.browserPath().map(platform::programLabel).orElse("Not configured"));
        fullScreenToggle.setSelected(settings.fullScreen());
        fullScreenToggle.setText(settings.fullScreen() ? "On" : "Off");
        for (var toggle : themeGroup.getToggles()) {
            toggle.setSelected(toggle.getUserData() == settings.theme());
        }
        // A hand-edited config.json may hold an interval the buttons do not offer
        // — 30 is legal — in which case none is selected, which is honest: the row
        // still takes the keyboard, and pressing either button adopts that value.
        Integer configuredInterval = settings.slideshowSeconds();
        // Spelled out beside the toggles, as the rows above spell out their
        // values: an interval the buttons do not offer selects neither of them,
        // and this line is then the only thing on screen that says what it is.
        slideshowValue.setText(configuredInterval + "s");
        for (ToggleButton toggle : slideshowToggles) {
            toggle.setSelected(configuredInterval.equals(toggle.getUserData()));
        }

        Integer configuredBuffer = settings.playerBufferSeconds();
        bufferValue.setText(configuredBuffer + "s");
        for (ToggleButton toggle : bufferToggles) {
            toggle.setSelected(configuredBuffer.equals(toggle.getUserData()));
        }

        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        rootsList.setItems(FXCollections.observableArrayList(settings.mediaRoots()));
        if (selected != null) {
            settings.rootById(selected.id()).ifPresent(root -> rootsList.getSelectionModel().select(root));
        } else if (!settings.mediaRoots().isEmpty()) {
            rootsList.getSelectionModel().selectFirst();
        }
    }

    private void update(ApplicationSettings updated) {
        onSettingsChanged.accept(updated);
        readSettings();
    }

    // -- actions ------------------------------------------------------------

    private void chooseVlcExecutable() {
        chooseProgram("Select the VLC " + platform.executableNoun(), settings().vlcPath())
                .ifPresent(program -> {
                    // Playback runs VLC with a command line and waits for it to exit,
                    // which needs the program itself. A bundle the platform could not
                    // resolve would be stored happily here and only fail later, at the
                    // point where the user can no longer tell what went wrong.
                    if (!Files.isRegularFile(program)) {
                        showStatus(vlcStatus, "That " + platform.executableNoun()
                                + " does not contain a program that can be started. "
                                + "Choose the VLC " + platform.executableNoun() + " itself.");
                        return;
                    }
                    update(settings().withVlcPath(Optional.of(program)));
                    showStatus(vlcStatus, "Using " + platform.programLabel(program));
                });
    }

    private void detectVlc() {
        showStatus(vlcStatus, "Looking for VLC…");
        FxTasks.run(
                context.backgroundExecutor(),
                platform::findVlc,
                found -> {
                    if (found.isPresent()) {
                        update(settings().withVlcPath(found));
                        showStatus(vlcStatus, "Found VLC at " + found.get());
                    } else {
                        showStatus(vlcStatus, "VLC was not found automatically. Choose it manually.");
                    }
                },
                failure -> showStatus(vlcStatus, "VLC could not be detected."));
    }

    private void chooseBrowser() {
        chooseProgram("Select a browser " + platform.executableNoun(), settings().browserPath())
                .ifPresent(program -> {
                    update(settings().withBrowserPath(Optional.of(program)));
                    showStatus(browserStatus, "Using " + platform.programLabel(program));
                });
    }

    /**
     * Picks a program and hands back something that can actually be started.
     *
     * <p>On macOS the picker returns an application bundle, which is a directory,
     * so the choice is resolved to the runnable file inside it before it is stored
     * — everything downstream expects to be able to run what it is given.
     */
    private Optional<Path> chooseProgram(String title, Optional<Path> configured) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        programStartingDirectory(configured.orElse(null)).ifPresent(chooser::setInitialDirectory);
        File chosen = chooser.showOpenDialog(window());
        if (chosen == null) {
            return Optional.empty();
        }
        rememberProgramDirectory(chosen.getParentFile());
        return Optional.of(platform.resolveProgram(chosen.toPath()));
    }

    /**
     * Where a picker should open: the configured location itself, then its parent,
     * then wherever the user browsed last. A path that no longer resolves — an
     * offline share, for example — must not stop the dialog from opening.
     */
    private Optional<File> startingDirectory(Path configured) {
        return configuredDirectory(configured)
                .or(() -> Optional.ofNullable(lastBrowsedDirectory).filter(File::isDirectory));
    }

    /**
     * Same, for programs, which keep their own memory: media folders and programs
     * live nowhere near each other, so browsing for one must not drag the other
     * picker along with it.
     *
     * <p>What is configured is the runnable file, and on macOS that sits inside an
     * application bundle — reopening at its parent would drop the user into
     * {@code Contents/MacOS} rather than the folder holding the bundle they need
     * to pick. Where programs live on this platform beats the last folder browsed.
     */
    private Optional<File> programStartingDirectory(Path configured) {
        return configuredProgramDirectory(configured)
                .or(() -> platform.programsDirectory().map(Path::toFile).filter(File::isDirectory))
                .or(() -> Optional.ofNullable(lastBrowsedProgramDirectory).filter(File::isDirectory));
    }

    /**
     * The folder holding the configured program — always the parent, never the
     * program itself, because a macOS bundle <em>is</em> a directory and opening
     * the picker inside it hides the very thing the user came to select.
     */
    private Optional<File> configuredProgramDirectory(Path configured) {
        if (configured == null) {
            return Optional.empty();
        }
        File parent = platform.presentableProgram(configured).toFile().getParentFile();
        return parent != null && parent.isDirectory() ? Optional.of(parent) : Optional.empty();
    }

    private static Optional<File> configuredDirectory(Path configured) {
        if (configured == null) {
            return Optional.empty();
        }
        File candidate = configured.toFile();
        if (candidate.isDirectory()) {
            return Optional.of(candidate);
        }
        File parent = candidate.getParentFile();
        return parent != null && parent.isDirectory() ? Optional.of(parent) : Optional.empty();
    }

    private void remember(File directory) {
        if (directory != null && directory.isDirectory()) {
            lastBrowsedDirectory = directory;
        }
    }

    private void rememberProgramDirectory(File directory) {
        if (directory != null && directory.isDirectory()) {
            lastBrowsedProgramDirectory = directory;
        }
    }

    private void addRoot() {
        editRoot(null).ifPresent(root -> update(settings().withRoot(root)));
    }

    private void editSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus(rootStatus, "Select a folder first.");
            return;
        }
        editRoot(selected).ifPresent(root -> update(settings().withRoot(root)));
    }

    private void removeSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus(rootStatus, "Select a folder first.");
            return;
        }
        update(settings().withoutRoot(selected.id()));
        showStatus(rootStatus, "Removed " + selected.displayName() + ".");
    }

    private void testSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus(rootStatus, "Select a folder first.");
            return;
        }
        showStatus(rootStatus, "Checking " + selected.displayPath() + "…");
        FxTasks.run(
                context.backgroundExecutor(),
                () -> {
                    context.scanner().verifyAccessible(selected.path());
                    // Only the count is wanted, and artwork does not change it —
                    // resolving it here would list every subfolder over the share
                    // to answer a question nobody asked.
                    return context.scanner()
                            .scanWithoutDirectoryArtwork(selected.path(), true).size();
                },
                count -> showStatus(rootStatus,
                        selected.displayName() + " is reachable — " + count + " items."),
                failure -> showStatus(rootStatus, friendlyMessage(failure)));
    }

    private static String friendlyMessage(Exception failure) {
        if (failure instanceof mediacenter.media.MediaAccessException accessFailure) {
            return accessFailure.userMessage();
        }
        return "This folder could not be opened.";
    }

    /** Add/edit dialog for a single media root. */
    private Optional<MediaRoot> editRoot(MediaRoot existing) {
        Dialog<MediaRoot> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(existing == null ? "Add media folder" : "Edit media folder");
        dialog.setHeaderText(existing == null ? "Add a media folder" : "Edit this media folder");
        dialog.setResizable(true);
        dialog.getDialogPane().getStyleClass().add("media-center-dialog");
        dialog.getDialogPane().getStylesheets().addAll(root.getScene() == null
                ? List.of()
                : root.getScene().getStylesheets());

        TextField nameField = new TextField(existing == null ? "" : existing.displayName());
        nameField.setPromptText("Movies");
        TextField pathField = new TextField(existing == null ? "" : existing.displayPath());
        pathField.setPromptText("\\\\synology\\video\\Movies");
        pathField.setPrefColumnCount(36);

        ComboBox<MediaRootType> typeBox = new ComboBox<>(
                FXCollections.observableArrayList(MediaRootType.values()));
        typeBox.getSelectionModel().select(existing == null ? MediaRootType.MOVIES : existing.type());

        Button browse = new Button("Browse for a folder…");
        browse.getStyleClass().add("primary-button");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select a media folder");
            String typed = pathField.getText() == null ? "" : pathField.getText().trim();
            startingDirectory(typed.isEmpty() ? null : Path.of(typed))
                    .ifPresent(chooser::setInitialDirectory);
            File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                remember(chosen.getParentFile());
                pathField.setText(chosen.getPath());
                if (nameField.getText().isBlank()) {
                    nameField.setText(chosen.getName());
                }
            }
        });

        // A network share that Windows has not mapped cannot be browsed to, so
        // typing a UNC path stays a first-class way to add a root.
        Label hint = new Label("Browse to a local or mapped folder, "
                + "or type a network path such as \\\\synology\\video\\Movies");
        hint.getStyleClass().add("dialog-hint");
        hint.setWrapText(true);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(16));
        form.addRow(0, new Label("Name"), nameField);
        form.addRow(1, new Label("Folder or network path"), pathField, browse);
        form.add(hint, 1, 2, 2, 1);
        form.addRow(3, new Label("Type"), typeBox);

        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            String path = pathField.getText() == null ? "" : pathField.getText().trim();
            if (path.isEmpty()) {
                return null;
            }
            String name = nameField.getText() == null || nameField.getText().isBlank()
                    ? path
                    : nameField.getText().trim();
            MediaRootType type = typeBox.getValue() == null ? MediaRootType.GENERAL : typeBox.getValue();
            return existing == null
                    ? MediaRoot.create(name, Path.of(path), type)
                    : existing.withDisplayName(name).withPath(Path.of(path)).withType(type);
        });

        // The path is what the viewer came here to set.
        javafx.application.Platform.runLater(pathField::requestFocus);
        return dialog.showAndWait();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
