package app.mls.desktop;

import app.mls.desktop.session.Vault;
import app.mls.desktop.ui.Async;
import app.mls.desktop.ui.AuthView;
import app.mls.desktop.ui.Theme;
import app.mls.desktop.ui.VaultView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point. Owns the {@link Vault} lifecycle and swaps the scene root between the
 * {@link AuthView} (locked) and the {@link VaultView} (unlocked). All crypto/network work happens
 * in the vault on a background thread; this class only orchestrates navigation.
 */
public final class DesktopApp extends Application {

    private DesktopConfig config = DesktopConfig.defaults();
    private Vault vault;
    private Scene scene;

    private final AuthView.Host host = new AuthView.Host() {
        @Override
        public Vault useServer(String serverUrl) {
            String url = (serverUrl == null || serverUrl.isBlank()) ? config.serverUrl() : serverUrl;
            if (!url.equals(config.serverUrl())) {
                if (vault != null) {
                    vault.close();
                }
                config = config.withServerUrl(url);
                vault = new Vault(config);
            } else if (vault == null) {
                vault = new Vault(config);
            }
            return vault;
        }

        @Override
        public void onUnlocked(Vault unlocked) {
            showVault();
        }
    };

    @Override
    public void start(Stage stage) {
        this.vault = new Vault(config);

        scene = new Scene(buildAuth(), 1000, 680);
        Theme.apply(scene);

        stage.setTitle("my-little-secrets");
        stage.setScene(scene);
        stage.setMinWidth(880);
        stage.setMinHeight(560);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private AuthView buildAuth() {
        return new AuthView(host, config.serverUrl(), vault.rememberedEmail(), vault.hasLocalAccount());
    }

    private void showAuth() {
        scene.setRoot(buildAuth());
    }

    private void showVault() {
        scene.setRoot(new VaultView(config, vault, this::showAuth));
    }

    private void shutdown() {
        if (vault != null) {
            vault.close();
            vault = null;
        }
        Async.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
