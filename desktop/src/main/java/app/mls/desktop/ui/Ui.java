package app.mls.desktop.ui;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Small node-builder helpers and an auto-clearing clipboard, shared by the views. */
public final class Ui {

    private Ui() {
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    /** An icon-only, quiet button (no border) with a tooltip — used in toolbars. */
    public static Button iconButton(String iconName, String tooltip, Runnable action) {
        Button b = new Button();
        b.setGraphic(Icons.icon(iconName, 18, Theme.TEXT_SECONDARY));
        b.getStyleClass().add("mls-icon-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Button textButton(String text, Runnable action, String... extraClasses) {
        Button b = new Button(text);
        b.getStyleClass().addAll(extraClasses);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Region hSpacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Region vSpacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Pos alignment, Node... children) {
        HBox h = new HBox(spacing, children);
        h.setAlignment(alignment);
        return h;
    }

    public static VBox column(double spacing, Node... children) {
        return new VBox(spacing, children);
    }

    /**
     * Copy {@code text} to the clipboard and schedule it to be cleared after {@code clearAfter} —
     * but only if the clipboard still holds OUR value (so we never wipe something the user copied
     * afterwards). Used for note bodies and the recovery code, which should not linger in paste history.
     */
    public static void copyEphemeral(String text, Duration clearAfter) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);

        PauseTransition pause = new PauseTransition(clearAfter);
        pause.setOnFinished(e -> {
            Clipboard cb = Clipboard.getSystemClipboard();
            if (text.equals(cb.getString())) {
                cb.clear();
            }
        });
        pause.play();
    }
}
