package app.mls.desktop.ui;

import app.mls.desktop.session.RegistrationOutcome;
import app.mls.desktop.session.Vault;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.util.Arrays;

/**
 * The sign-in / create-account screen. It collects credentials, runs the chosen {@link Vault}
 * operation off the FX thread, and on success hands control back to the host to open the vault.
 * The password is read from the field, used, and the local {@code char[]} wiped; registration shows
 * the one-time recovery code before proceeding.
 */
public final class AuthView extends StackPane {

    /** Bridges the view to the app: resolves a vault for a server URL, and reacts to unlock. */
    public interface Host {
        Vault useServer(String serverUrl);

        void onUnlocked(Vault vault);
    }

    private enum Mode { SIGN_IN, REGISTER }

    private final Host host;
    private Mode mode = Mode.SIGN_IN;

    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField confirm = new PasswordField();
    private final TextField server = new TextField();
    private final Label status = Ui.label("", "mls-meta");

    private final Button primary = Ui.textButton("Sign in", this::onPrimary, "mls-primary");
    private final Button offline = Ui.textButton("Unlock offline", this::onOffline, "mls-icon-button");
    private final Button toggle = Ui.textButton("Create account", this::flipMode, "mls-icon-button");
    private final HBox confirmRow;

    public AuthView(Host host, String defaultServerUrl, String rememberedEmail, boolean hasLocalAccount) {
        this.host = host;

        getStyleClass().add("mls-app");
        setPadding(new Insets(40));

        Label wordmark = Ui.label("my-little-secrets", "mls-display");
        Label tagline = Ui.label("End-to-end encrypted notes. Only you hold the key.", "mls-meta");

        email.setPromptText("you@example.com");
        email.setText(rememberedEmail == null ? "" : rememberedEmail);
        password.setPromptText("Master password");
        confirm.setPromptText("Confirm master password");
        server.setText(defaultServerUrl);
        server.setPromptText("https://your-server");

        confirmRow = labeled("Confirm", confirm);
        confirmRow.setVisible(false);
        confirmRow.setManaged(false);

        offline.setVisible(hasLocalAccount);
        offline.setManaged(hasLocalAccount);

        VBox card = Ui.column(16,
                wordmark,
                tagline,
                spacer(8),
                labeled("Email", email),
                labeled("Password", password),
                confirmRow,
                labeled("Server", server),
                status,
                Ui.row(10, Pos.CENTER_LEFT, offline, Ui.hSpacer(), toggle, primary));
        card.getStyleClass().add("mls-surface");
        card.setPadding(new Insets(28));
        card.setMaxWidth(440);
        card.setFillWidth(true);

        getChildren().add(card);
        setAlignment(card, Pos.CENTER);

        password.setOnAction(e -> onPrimary());
        confirm.setOnAction(e -> onPrimary());
    }

    private void flipMode() {
        mode = (mode == Mode.SIGN_IN) ? Mode.REGISTER : Mode.SIGN_IN;
        boolean reg = mode == Mode.REGISTER;
        confirmRow.setVisible(reg);
        confirmRow.setManaged(reg);
        primary.setText(reg ? "Create account" : "Sign in");
        toggle.setText(reg ? "Have an account?" : "Create account");
        offline.setVisible(!reg && offline.isManaged());
        status.setText("");
    }

    private void onPrimary() {
        String addr = email.getText().trim();
        if (!addr.contains("@") || addr.length() < 3) {
            status.setText("Enter a valid email address.");
            return;
        }
        if (password.getText().isEmpty()) {
            status.setText("Enter your master password.");
            return;
        }
        if (mode == Mode.REGISTER && !password.getText().equals(confirm.getText())) {
            status.setText("Passwords don't match.");
            return;
        }
        Vault vault = host.useServer(server.getText().trim());
        char[] pw = password.getText().toCharArray();
        setBusy(true, mode == Mode.REGISTER ? "Creating your account…" : "Signing in…");

        if (mode == Mode.REGISTER) {
            Async.run(() -> vault.register(addr, pw),
                    outcome -> {
                        Arrays.fill(pw, '\0');
                        afterRegister(vault, outcome);
                    },
                    err -> {
                        Arrays.fill(pw, '\0');
                        fail(err);
                    });
        } else {
            Async.run(() -> {
                        vault.login(addr, pw);
                        return null;
                    },
                    ignored -> {
                        Arrays.fill(pw, '\0');
                        host.onUnlocked(vault);
                    },
                    err -> {
                        Arrays.fill(pw, '\0');
                        fail(err);
                    });
        }
    }

    private void onOffline() {
        if (password.getText().isEmpty()) {
            status.setText("Enter your master password to unlock offline.");
            return;
        }
        Vault vault = host.useServer(server.getText().trim());
        char[] pw = password.getText().toCharArray();
        setBusy(true, "Unlocking…");
        Async.run(() -> {
                    vault.unlockOffline(pw);
                    return null;
                },
                ignored -> {
                    Arrays.fill(pw, '\0');
                    host.onUnlocked(vault);
                },
                err -> {
                    Arrays.fill(pw, '\0');
                    fail(err);
                });
    }

    private void afterRegister(Vault vault, RegistrationOutcome outcome) {
        if (outcome.hasRecoveryCode()) {
            Dialogs.recoveryCode(getScene().getWindow(), outcome.recoveryCode(), () -> host.onUnlocked(vault));
        } else {
            host.onUnlocked(vault);
        }
    }

    private void fail(Throwable err) {
        setBusy(false, err.getMessage() == null ? "Something went wrong." : err.getMessage());
    }

    private void setBusy(boolean busy, String message) {
        email.setDisable(busy);
        password.setDisable(busy);
        confirm.setDisable(busy);
        server.setDisable(busy);
        primary.setDisable(busy);
        offline.setDisable(busy);
        toggle.setDisable(busy);
        status.setText(message);
    }

    private HBox labeled(String text, Node field) {
        Label l = Ui.label(text, "mls-ui-label");
        l.setMinWidth(72);
        if (field instanceof Region r) {
            HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        }
        return Ui.row(12, Pos.CENTER_LEFT, l, field);
    }

    private Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }
}
