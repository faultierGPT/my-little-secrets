package app.mls.desktop.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.function.Consumer;

/** Themed modal dialogs: the one-time recovery code, a destructive-action confirm, password change. */
public final class Dialogs {

    private static final Duration CLIPBOARD_CLEAR = Duration.seconds(30);

    private Dialogs() {
    }

    /** Reveal the one-time recovery code; {@code onConfirm} fires only after the user acknowledges it. */
    public static void recoveryCode(Window owner, String code, Runnable onConfirm) {
        Label heading = Ui.label("Save your recovery code", "mls-title");
        Label blurb = Ui.label(
                "This is the ONLY way to recover your notes if you forget your password. "
                        + "We can't reset it for you — store this code somewhere safe and private.",
                "mls-ui-body");
        blurb.setWrapText(true);

        TextArea codeField = new TextArea(code);
        codeField.getStyleClass().add("mls-reading");
        codeField.setEditable(false);
        codeField.setWrapText(true);
        codeField.setPrefRowCount(2);
        codeField.setFocusTraversable(false);

        Button copy = Ui.textButton("Copy", () -> Ui.copyEphemeral(code, CLIPBOARD_CLEAR));
        copy.setGraphic(Icons.icon("copy", 16, Theme.TEXT_PRIMARY));

        CheckBox saved = new CheckBox("I've stored my recovery code somewhere safe");
        saved.getStyleClass().add("mls-ui-body");

        Stage stage = new Stage();
        Button cont = Ui.textButton("Continue", () -> {
            stage.close();
            onConfirm.run();
        }, "mls-primary");
        cont.setDisable(true);
        saved.selectedProperty().addListener((o, was, is) -> cont.setDisable(!is));

        VBox body = Ui.column(14,
                Ui.row(10, Pos.CENTER_LEFT, Icons.icon("shield", 22, Theme.ACCENT), heading),
                blurb,
                codeField,
                Ui.row(10, Pos.CENTER_LEFT, copy, Ui.hSpacer()),
                saved,
                Ui.row(10, Pos.CENTER_RIGHT, cont));
        show(owner, stage, "Recovery code", body, 460);
    }

    /** Confirm a destructive action. {@code onConfirm} runs on the FX thread if the user proceeds. */
    public static void confirm(Window owner, String message, String confirmLabel, Runnable onConfirm) {
        Stage stage = new Stage();
        Label text = Ui.label(message, "mls-ui-body");
        text.setWrapText(true);
        Button cancel = Ui.textButton("Cancel", stage::close);
        Button ok = Ui.textButton(confirmLabel, () -> {
            stage.close();
            onConfirm.run();
        }, "mls-danger");
        VBox body = Ui.column(16, text, Ui.row(10, Pos.CENTER_RIGHT, cancel, ok));
        show(owner, stage, "Please confirm", body, 380);
    }

    /** Handles the actual (async) password change; reports completion/failure back to the dialog. */
    public interface PasswordChangeHandler {
        void submit(char[] current, char[] next, Runnable onDone, Consumer<String> onError);
    }

    public static void changePassword(Window owner, PasswordChangeHandler handler) {
        Stage stage = new Stage();
        PasswordField current = new PasswordField();
        current.setPromptText("Current password");
        PasswordField next = new PasswordField();
        next.setPromptText("New password");
        PasswordField confirm = new PasswordField();
        confirm.setPromptText("Confirm new password");
        Label error = Ui.label("", "mls-meta");
        error.setWrapText(true);

        Button cancel = Ui.textButton("Cancel", stage::close);
        Button submit = Ui.textButton("Change password", () -> { }, "mls-primary");
        submit.setOnAction(e -> {
            error.setText("");
            String n = next.getText();
            if (n.isEmpty() || !n.equals(confirm.getText())) {
                error.setText("New passwords must match and be non-empty.");
                return;
            }
            submit.setDisable(true);
            cancel.setDisable(true);
            char[] cur = current.getText().toCharArray();
            char[] nw = n.toCharArray();
            handler.submit(cur, nw, stage::close, msg -> {
                error.setText(msg);
                submit.setDisable(false);
                cancel.setDisable(false);
            });
        });

        VBox body = Ui.column(12,
                Ui.label("Change master password", "mls-title"),
                current, next, confirm, error,
                Ui.row(10, Pos.CENTER_RIGHT, cancel, submit));
        show(owner, stage, "Change password", body, 380);
    }

    private static void show(Window owner, Stage stage, String title, VBox body, double width) {
        body.getStyleClass().add("mls-overlay");
        body.setPadding(new Insets(20));
        StackPane root = new StackPane(body);
        root.getStyleClass().add("mls-app");
        root.setPadding(new Insets(24));
        Scene scene = new Scene(root, width, body.prefHeight(width) + 88);
        Theme.apply(scene);
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(title);
        stage.setScene(scene);
        stage.sizeToScene();
        stage.showAndWait();
    }
}
