package app.mls.desktop.ui;

import app.mls.desktop.DesktopConfig;
import app.mls.desktop.session.NoteView;
import app.mls.desktop.session.Vault;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The unlocked workspace: a searchable note list on the left, an editor on the right. Edits are
 * encrypted and persisted locally as you type (debounced auto-save); sync reconciles with the server.
 * An idle timer re-locks the vault — wiping the in-memory account key — after the configured timeout.
 */
public final class VaultView extends BorderPane {

    private static final Duration CLIPBOARD_CLEAR = Duration.seconds(30);
    private static final Duration SAVE_DEBOUNCE = Duration.millis(900);

    private final Vault vault;
    private final Runnable onLock;

    private final ObservableList<NoteView> notes = FXCollections.observableArrayList();
    private final FilteredList<NoteView> filtered = new FilteredList<>(notes, n -> true);
    private final ListView<NoteView> list = new ListView<>(filtered);

    private final TextField search = new TextField();
    private final TextField titleField = new TextField();
    private final TextField tagsField = new TextField();
    private final TextArea bodyArea = new TextArea();
    private final Label savedHint = Ui.label("", "mls-meta");
    private final Label syncStatus = Ui.label("", "mls-meta");

    private final PauseTransition saveDebounce = new PauseTransition(SAVE_DEBOUNCE);
    private final PauseTransition idleTimer;

    private String editingId;       // id in the editor; null = new unsaved note
    private boolean editorDirty;
    private boolean loadingEditor;  // guards field listeners while we populate the editor
    private boolean syncingSelection; // guards the list listener during programmatic selection
    private long editSeq;

    public VaultView(DesktopConfig config, Vault vault, Runnable onLock) {
        this.vault = vault;
        this.onLock = onLock;
        this.idleTimer = new PauseTransition(Duration.millis(Math.max(10_000, config.autoLock().toMillis())));

        getStyleClass().add("mls-app");
        setTop(buildAppBar());
        setLeft(buildSidebar());
        setCenter(buildEditor());
        setBottom(buildStatusBar());

        wireSelection();
        wireEditorAutoSave();
        wireSearch();
        wireAutoLock();

        refreshFromVault(null);
    }

    // ---------- top app bar ----------

    private HBox buildAppBar() {
        Label wordmark = Ui.label("my-little-secrets", "mls-ui-label");
        wordmark.setGraphic(Icons.icon("shield", 18, Theme.ACCENT));

        var syncBtn = Ui.iconButton("sync", "Sync now", this::doSync);
        var lockBtn = Ui.iconButton("lock", "Lock vault", this::doLock);

        MenuButton menu = new MenuButton();
        menu.setGraphic(Icons.icon("settings", 18, Theme.TEXT_SECONDARY));
        menu.getStyleClass().add("mls-icon-button");
        MenuItem changePw = new MenuItem("Change password…");
        changePw.setOnAction(e -> onChangePassword());
        MenuItem signOut = new MenuItem("Lock & sign out");
        signOut.setOnAction(e -> doLock());
        menu.getItems().addAll(changePw, signOut);

        HBox bar = Ui.row(8, Pos.CENTER_LEFT, wordmark, Ui.hSpacer(), syncBtn, lockBtn, menu);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.getStyleClass().add("mls-app");
        return bar;
    }

    // ---------- left sidebar (search + list) ----------

    private VBox buildSidebar() {
        search.setPromptText("Search notes");
        HBox.setHgrow(search, Priority.ALWAYS);

        var newBtn = Ui.iconButton("plus", "New note", this::newNote);
        HBox top = Ui.row(8, Pos.CENTER_LEFT, Icons.icon("search", 16, Theme.TEXT_SECONDARY), search, newBtn);

        list.setCellFactory(v -> new NoteCell());
        list.setPlaceholder(Ui.label("No notes yet — press + to write one.", "mls-meta"));
        VBox.setVgrow(list, Priority.ALWAYS);

        VBox box = Ui.column(10, top, list);
        box.setPadding(new Insets(12));
        box.setPrefWidth(320);
        box.setMinWidth(260);
        box.getStyleClass().add("mls-app");
        return box;
    }

    // ---------- right editor ----------

    private VBox buildEditor() {
        titleField.setPromptText("Title");
        titleField.getStyleClass().add("mls-title");

        tagsField.setPromptText("tags, comma separated");
        tagsField.getStyleClass().add("mls-meta");

        bodyArea.setPromptText("Write something only you can read…");
        bodyArea.getStyleClass().add("mls-reading");
        bodyArea.setWrapText(true);
        VBox.setVgrow(bodyArea, Priority.ALWAYS);

        var copyBtn = Ui.textButton("Copy", this::copyBody, "mls-icon-button");
        copyBtn.setGraphic(Icons.icon("copy", 16, Theme.TEXT_SECONDARY));
        var deleteBtn = Ui.textButton("Delete", this::deleteCurrent, "mls-danger");
        deleteBtn.setGraphic(Icons.icon("trash", 16, Theme.DANGER));

        HBox footer = Ui.row(10, Pos.CENTER_LEFT, savedHint, Ui.hSpacer(), copyBtn, deleteBtn);

        VBox editor = Ui.column(12, titleField, tagsField, bodyArea, footer);
        editor.setPadding(new Insets(16, 20, 16, 20));
        editor.getStyleClass().add("mls-app");
        return editor;
    }

    private HBox buildStatusBar() {
        HBox bar = Ui.row(8, Pos.CENTER_LEFT, syncStatus, Ui.hSpacer(),
                Ui.label(vault.isOnline() ? "online" : "offline", "mls-meta"));
        bar.setPadding(new Insets(8, 16, 8, 16));
        bar.getStyleClass().add("mls-app");
        return bar;
    }

    // ---------- wiring ----------

    private void wireSelection() {
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (syncingSelection || sel == null) {
                return;
            }
            autoSaveThen(() -> loadIntoEditor(sel));
        });
    }

    private void wireEditorAutoSave() {
        saveDebounce.setOnFinished(e -> saveCurrent());
        Runnable onEdit = () -> {
            if (loadingEditor) {
                return;
            }
            editorDirty = true;
            editSeq++;
            savedHint.setText("Editing…");
            saveDebounce.playFromStart();
        };
        titleField.textProperty().addListener((o, a, b) -> onEdit.run());
        tagsField.textProperty().addListener((o, a, b) -> onEdit.run());
        bodyArea.textProperty().addListener((o, a, b) -> onEdit.run());
    }

    private void wireSearch() {
        search.textProperty().addListener((o, a, q) -> {
            String needle = q == null ? "" : q.trim().toLowerCase();
            filtered.setPredicate(note -> matches(note, needle));
        });
    }

    private void wireAutoLock() {
        idleTimer.setOnFinished(e -> doLock());
        addEventFilter(javafx.scene.input.KeyEvent.ANY, e -> idleTimer.playFromStart());
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> idleTimer.playFromStart());
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> idleTimer.playFromStart());
        addEventFilter(javafx.scene.input.ScrollEvent.ANY, e -> idleTimer.playFromStart());
        idleTimer.playFromStart();
    }

    private static boolean matches(NoteView note, String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return note.title().toLowerCase().contains(needle)
                || note.body().toLowerCase().contains(needle)
                || note.tags().stream().anyMatch(t -> t.toLowerCase().contains(needle));
    }

    // ---------- editor operations ----------

    private void loadIntoEditor(NoteView note) {
        loadingEditor = true;
        editingId = note.id();
        titleField.setText(note.title());
        tagsField.setText(String.join(", ", note.tags()));
        bodyArea.setText(note.body());
        editorDirty = false;
        savedHint.setText(note.dirty() ? "Unsynced" : "");
        loadingEditor = false;
    }

    private void newNote() {
        autoSaveThen(() -> {
            syncingSelection = true;
            list.getSelectionModel().clearSelection();
            syncingSelection = false;
            loadingEditor = true;
            editingId = null;
            titleField.clear();
            tagsField.clear();
            bodyArea.clear();
            editorDirty = false;
            loadingEditor = false;
            savedHint.setText("New note");
            titleField.requestFocus();
        });
    }

    private boolean editorBlank() {
        return titleField.getText().isBlank() && bodyArea.getText().isBlank() && tagsField.getText().isBlank();
    }

    /** Persist current editor content (if there is anything worth saving), then run {@code then}. */
    private void autoSaveThen(Runnable then) {
        if (!editorDirty || (editingId == null && editorBlank())) {
            then.run();
            return;
        }
        long at = editSeq;
        String title = titleField.getText();
        String body = bodyArea.getText();
        List<String> tags = parseTags(tagsField.getText());
        savedHint.setText("Saving…");
        Async.run(() -> vault.saveNote(editingId, title, body, tags),
                id -> {
                    if (editingId == null) {
                        editingId = id;
                    }
                    if (editSeq == at) {
                        editorDirty = false;
                    }
                    upsertListEntry(id, title, body, tags);
                    then.run();
                },
                err -> {
                    savedHint.setText(message(err));
                    then.run();
                });
    }

    private void saveCurrent() {
        autoSaveThen(() -> savedHint.setText("Saved"));
    }

    private void deleteCurrent() {
        if (editingId == null) {
            return;
        }
        String id = editingId;
        Dialogs.confirm(getScene().getWindow(), "Delete this note? This can't be undone.", "Delete", () ->
                Async.run(() -> {
                            vault.deleteNote(id);
                            return null;
                        },
                        ignored -> {
                            notes.removeIf(n -> id.equals(n.id()));
                            newNote();
                            savedHint.setText("Deleted");
                        },
                        err -> savedHint.setText(message(err))));
    }

    private void copyBody() {
        if (!bodyArea.getText().isEmpty()) {
            Ui.copyEphemeral(bodyArea.getText(), CLIPBOARD_CLEAR);
            savedHint.setText("Copied (clears in 30s)");
        }
    }

    // ---------- sync / lock / account ----------

    private void doSync() {
        autoSaveThen(() -> {
            syncStatus.setText("Syncing…");
            Async.run(vault::sync,
                    summary -> {
                        syncStatus.setText(summary.describe());
                        refreshFromVault(editingId);
                    },
                    err -> syncStatus.setText(message(err)));
        });
    }

    private void doLock() {
        idleTimer.stop();
        saveDebounce.stop();
        autoSaveThen(() -> {
            vault.lock();
            onLock.run();
        });
    }

    private void onChangePassword() {
        Dialogs.changePassword(getScene().getWindow(), (current, next, onDone, onError) ->
                Async.run(() -> {
                            vault.changePassword(current, next);
                            return null;
                        },
                        ignored -> {
                            Arrays.fill(current, '\0');
                            Arrays.fill(next, '\0');
                            syncStatus.setText("Password changed");
                            onDone.run();
                        },
                        err -> {
                            Arrays.fill(current, '\0');
                            Arrays.fill(next, '\0');
                            onError.accept(message(err));
                        }));
    }

    // ---------- list maintenance ----------

    private void refreshFromVault(String selectId) {
        Async.run(vault::notes,
                fresh -> {
                    notes.setAll(fresh);
                    if (selectId != null) {
                        selectQuietly(selectId);
                    } else if (editingId == null && editorBlank() && !editorDirty
                            && list.getSelectionModel().getSelectedItem() == null && !filtered.isEmpty()) {
                        // Open the most-recent note by default (fires the editor load).
                        list.getSelectionModel().select(0);
                    }
                },
                err -> syncStatus.setText(message(err)));
    }

    private void upsertListEntry(String id, String title, String body, List<String> tags) {
        NoteView updated = new NoteView(id, title, body, tags, System.currentTimeMillis(), true);
        List<NoteView> copy = new ArrayList<>(notes);
        copy.removeIf(n -> id.equals(n.id()));
        copy.add(updated);
        copy.sort(Comparator.comparingLong(NoteView::updatedAt).reversed());
        notes.setAll(copy);
        selectQuietly(id);
    }

    private void selectQuietly(String id) {
        syncingSelection = true;
        for (NoteView n : filtered) {
            if (id.equals(n.id())) {
                list.getSelectionModel().select(n);
                break;
            }
        }
        syncingSelection = false;
    }

    private static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,\\n]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String message(Throwable err) {
        return err.getMessage() == null ? "Something went wrong." : err.getMessage();
    }

    // ---------- note list cell ----------

    private static final class NoteCell extends ListCell<NoteView> {
        private final Label title = Ui.label("", "mls-ui-label");
        private final Label snippet = Ui.label("", "mls-meta");
        private final Circle dot = new Circle(3, Theme.ACCENT);
        private final HBox titleRow;
        private final VBox box;

        NoteCell() {
            snippet.setWrapText(false);
            titleRow = Ui.row(6, Pos.CENTER_LEFT, title, dot);
            box = Ui.column(2, titleRow, snippet);
            box.setPadding(new Insets(2, 0, 2, 0));
        }

        @Override
        protected void updateItem(NoteView item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            title.setText(item.displayTitle());
            String snip = item.snippet();
            snippet.setText(snip.isEmpty() ? "No additional text" : snip);
            dot.setVisible(item.dirty());
            dot.setManaged(item.dirty());
            setGraphic(box);
        }
    }
}
