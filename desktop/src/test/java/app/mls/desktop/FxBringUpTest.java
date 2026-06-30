package app.mls.desktop;

import app.mls.desktop.session.Vault;
import app.mls.desktop.ui.AuthView;
import app.mls.desktop.ui.Theme;
import app.mls.desktop.ui.VaultView;
import javafx.application.Platform;
import javafx.scene.Scene;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Best-effort headless bring-up: constructs the real scene graphs (sign-in + workspace) on the FX
 * thread under Monocle and forces a CSS pass, catching stylesheet/resource/node-construction errors.
 * Where no JavaFX toolkit can initialize (a bare sandbox with no GL/X11), it SKIPS rather than fails —
 * the authoritative behavioral verification is {@link VaultIntegrationTest}, which needs no display.
 */
class FxBringUpTest {

    private static boolean fxUp;

    @BeforeAll
    static void initToolkit() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            if (!latch.await(10, TimeUnit.SECONDS)) {
                fxUp = false;
                return;
            }
            // A bare sandbox may bring up the graphics pipeline but have NO fonts (no fontconfig),
            // which makes the CSS/text subsystem unusable. Probe it so we skip rather than fail.
            CountDownLatch probe = new CountDownLatch(1);
            AtomicReference<Throwable> fontError = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    javafx.scene.text.Font.getDefault();
                } catch (Throwable t) {
                    fontError.set(t);
                } finally {
                    probe.countDown();
                }
            });
            fxUp = probe.await(10, TimeUnit.SECONDS) && fontError.get() == null;
        } catch (Throwable t) {
            fxUp = false;
        }
    }

    @Test
    void scenesBuildAndStyleHeadlessly(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(fxUp, "JavaFX toolkit unavailable in this environment — skipping UI bring-up");

        DesktopConfig cfg = new DesktopConfig("http://127.0.0.1:1", tmp, Duration.ofMinutes(5));
        Vault vault = new Vault(cfg);
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Platform.runLater(() -> {
            try {
                AuthView.Host host = new AuthView.Host() {
                    @Override
                    public Vault useServer(String serverUrl) {
                        return vault;
                    }

                    @Override
                    public void onUnlocked(Vault unlocked) {
                    }
                };

                AuthView auth = new AuthView(host, cfg.serverUrl(), "", false);
                Scene authScene = new Scene(auth, 900, 640);
                Theme.apply(authScene);
                auth.applyCss();
                auth.layout();

                VaultView workspace = new VaultView(cfg, vault, () -> { });
                Scene vaultScene = new Scene(workspace, 900, 640);
                Theme.apply(vaultScene);
                workspace.applyCss();
                workspace.layout();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(20, TimeUnit.SECONDS), "FX scene construction timed out");
        vault.close();
        if (error.get() != null) {
            throw new AssertionError("scene construction/styling failed", error.get());
        }
    }
}
