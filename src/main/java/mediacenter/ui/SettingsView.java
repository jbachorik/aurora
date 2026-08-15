package mediacenter.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import mediacenter.config.ApplicationSettings;
import mediacenter.media.MediaRoot;
import mediacenter.media.MediaRootType;
import mediacenter.platform.PlatformServices;

/**
 * Settings kept deliberately flat: one screen, large rows, no nested pages.
 */
public final class SettingsView implements View {

    private final UiContext context;
    private final PlatformServices platform;
    private final Consumer<ApplicationSettings> onSettingsChanged;

    private final VBox root = new VBox();
    private final Label vlcValue = new Label();
    private final Label browserValue = new Label();
    private final ToggleButton fullScreenToggle = new ToggleButton();
    private final ListView<MediaRoot> rootsList = new ListView<>();
    private final Label rootStatus = new Label();
    private final Button firstFocusTarget;

    public SettingsView(UiContext context, PlatformServices platform, Consumer<ApplicationSettings> onSettingsChanged) {
        this.context = context;
        this.platform = platform;
        this.onSettingsChanged = onSettingsChanged;

        root.getStyleClass().add("settings-view");
        root.setSpacing(18);
        root.setPadding(new Insets(24, 32, 24, 32));

        Button chooseVlc = new Button("Choose vlc.exe…");
        chooseVlc.setOnAction(event -> chooseVlcExecutable());
        this.firstFocusTarget = chooseVlc;

        Button detectVlc = new Button("Detect");
        detectVlc.setOnAction(event -> detectVlc());

        Button chooseBrowser = new Button("Choose browser…");
        chooseBrowser.setOnAction(event -> chooseBrowser());
        Button clearBrowser = new Button("Clear");
        clearBrowser.setOnAction(event -> update(settings().withBrowserPath(Optional.empty())));

        fullScreenToggle.setOnAction(event ->
                update(settings().withFullScreen(fullScreenToggle.isSelected())));

        root.getChildren().addAll(
                settingRow("VLC player", vlcValue, chooseVlc, detectVlc),
                settingRow("Browser (optional)", browserValue, chooseBrowser, clearBrowser),
                settingRow("Full screen", new Label(), fullScreenToggle),
                mediaRootsSection());

        readSettings();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public void focusSelection() {
        firstFocusTarget.requestFocus();
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

    private Node settingRow(String label, Label value, Button... buttons) {
        Label name = new Label(label);
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);

        // The value is the only part allowed to shrink: a long UNC path must
        // ellipsize rather than squeeze the buttons until their labels are cut.
        value.getStyleClass().add("setting-value");
        HBox.setHgrow(value, Priority.ALWAYS);
        value.setMaxWidth(Double.MAX_VALUE);
        value.setMinWidth(80);

        HBox row = new HBox(16, name, value);
        row.getStyleClass().add("setting-row");
        row.setAlignment(Pos.CENTER_LEFT);
        for (Button button : buttons) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
        row.getChildren().addAll(buttons);
        return row;
    }

    private Node settingRow(String label, Label value, ToggleButton toggle) {
        Label name = new Label(label);
        name.getStyleClass().add("setting-name");
        name.setMinWidth(320);
        HBox.setHgrow(value, Priority.ALWAYS);
        value.setMaxWidth(Double.MAX_VALUE);
        toggle.setMinWidth(Region.USE_PREF_SIZE);

        HBox row = new HBox(16, name, value, toggle);
        row.getStyleClass().add("setting-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
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

        Button add = new Button("Add");
        add.setOnAction(event -> addRoot());
        Button edit = new Button("Edit");
        edit.setOnAction(event -> editSelectedRoot());
        Button remove = new Button("Remove");
        remove.setOnAction(event -> removeSelectedRoot());
        Button test = new Button("Test");
        test.setOnAction(event -> testSelectedRoot());

        ButtonBar buttons = new ButtonBar();
        buttons.setButtonMinWidth(160);
        buttons.getButtons().addAll(add, edit, remove, test);
        ButtonBar.setButtonData(add, ButtonBar.ButtonData.LEFT);
        ButtonBar.setButtonData(edit, ButtonBar.ButtonData.LEFT);
        ButtonBar.setButtonData(remove, ButtonBar.ButtonData.LEFT);
        ButtonBar.setButtonData(test, ButtonBar.ButtonData.LEFT);

        rootStatus.getStyleClass().add("setting-status");
        rootStatus.setWrapText(true);

        VBox section = new VBox(12, heading, rootsList, buttons, rootStatus);
        VBox.setVgrow(section, Priority.ALWAYS);
        return section;
    }

    // -- state --------------------------------------------------------------

    private ApplicationSettings settings() {
        return context.settings().get();
    }

    private void readSettings() {
        ApplicationSettings settings = settings();
        vlcValue.setText(settings.vlcPath().map(Path::toString).orElse("Not configured"));
        browserValue.setText(settings.browserPath().map(Path::toString).orElse("Not configured"));
        fullScreenToggle.setSelected(settings.fullScreen());
        fullScreenToggle.setText(settings.fullScreen() ? "On" : "Off");

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
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select the VLC program");
        settings().vlcPath()
                .map(Path::getParent)
                .map(Path::toFile)
                .filter(File::isDirectory)
                .ifPresent(chooser::setInitialDirectory);
        File chosen = chooser.showOpenDialog(window());
        if (chosen != null) {
            update(settings().withVlcPath(Optional.of(chosen.toPath())));
        }
    }

    private void detectVlc() {
        rootStatus.setText("Looking for VLC…");
        FxTasks.run(
                context.backgroundExecutor(),
                platform::findVlc,
                found -> {
                    if (found.isPresent()) {
                        update(settings().withVlcPath(found));
                        rootStatus.setText("Found VLC at " + found.get());
                    } else {
                        rootStatus.setText("VLC was not found automatically. Choose the program manually.");
                    }
                },
                failure -> rootStatus.setText("VLC could not be detected."));
    }

    private void chooseBrowser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select a browser program");
        File chosen = chooser.showOpenDialog(window());
        if (chosen != null) {
            update(settings().withBrowserPath(Optional.of(chosen.toPath())));
        }
    }

    private void addRoot() {
        editRoot(null).ifPresent(root -> update(settings().withRoot(root)));
    }

    private void editSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            rootStatus.setText("Select a folder first.");
            return;
        }
        editRoot(selected).ifPresent(root -> update(settings().withRoot(root)));
    }

    private void removeSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            rootStatus.setText("Select a folder first.");
            return;
        }
        update(settings().withoutRoot(selected.id()));
        rootStatus.setText("Removed " + selected.displayName() + ".");
    }

    private void testSelectedRoot() {
        MediaRoot selected = rootsList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            rootStatus.setText("Select a folder first.");
            return;
        }
        rootStatus.setText("Checking " + selected.displayPath() + "…");
        FxTasks.run(
                context.backgroundExecutor(),
                () -> {
                    context.scanner().verifyAccessible(selected.path());
                    return context.scanner().scan(selected.path()).size();
                },
                count -> rootStatus.setText(selected.displayName() + " is reachable — " + count + " items."),
                failure -> rootStatus.setText(friendlyMessage(failure)));
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

        Button browse = new Button("Browse…");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select a media folder");
            File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen != null) {
                pathField.setText(chosen.getPath());
                if (nameField.getText().isBlank()) {
                    nameField.setText(chosen.getName());
                }
            }
        });

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.setPadding(new Insets(16));
        form.addRow(0, new Label("Name"), nameField);
        form.addRow(1, new Label("Folder or UNC path"), pathField, browse);
        form.addRow(2, new Label("Type"), typeBox);

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

        return dialog.showAndWait();
    }

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }
}
