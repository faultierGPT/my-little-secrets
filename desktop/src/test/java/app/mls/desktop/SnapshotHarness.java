package app.mls.desktop;

import app.mls.desktop.session.Vault;
import app.mls.desktop.ui.AuthView;
import app.mls.desktop.ui.Theme;
import app.mls.desktop.ui.VaultView;
import app.mls.server.Embedded;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

/**
 * Renders the REAL desktop UI to PNGs for visual verification. Gated on {@code -Dmls.snapshot.dir}
 * (and a font directory via {@code -Dprism.fontdir}) so it never runs in a normal build — it is a
 * deliberate, on-demand way to capture how the app actually looks, driven by genuine demo data that
 * round-tripped through the real server.
 */
@EnabledIfSystemProperty(named = "mls.snapshot.dir", matches = ".+")
class SnapshotHarness {

    @Test
    void renderSignInAndWorkspace() throws Exception {
        Path outDir = Paths.get(System.getProperty("mls.snapshot.dir"));
        Files.createDirectories(outDir);
        Path data = Files.createTempDirectory("mls-snapshot");
        String dbName = "snap_" + UUID.randomUUID().toString().replace("-", "");

        try (Embedded.Handle server = Embedded.start(Embedded.h2Config(dbName))) {
            DesktopConfig cfg = new DesktopConfig("http://127.0.0.1:" + server.port, data, Duration.ofMinutes(30));
            Vault vault = new Vault(cfg);
            vault.register("demo@little.secrets", "demo password correct horse".toCharArray());
            vault.saveNote(null, "Welcome to my-little-secrets",
                    "Everything here is end-to-end encrypted. The server only ever stores ciphertext — "
                            + "your master password and keys never leave this device.", List.of("intro"));
            vault.saveNote(null, "Travel checklist",
                    "Passport, chargers, the good headphones. Boarding passes in the side pocket.",
                    List.of("travel", "todo"));
            vault.saveNote(null, "Wine to remember",
                    "2016 Barolo from the little shop near the river. Ask for Marco.", List.of("food"));
            vault.saveNote(null, "Rotation reminder",
                    "Rotate the deploy key before the 1st. Backup pass-phrase lives in the safe, not here.",
                    List.of("ops"));
            vault.sync();

            startFx();

            renderNode(outDir.resolve("desktop-signin.png"), 980, 660,
                    () -> new AuthView(host(vault), cfg.serverUrl(), "demo@little.secrets", true));

            renderWorkspace(outDir.resolve("desktop-workspace.png"), 1060, 690, cfg, vault);

            vault.close();
        }
        Platform.exit();
    }

    private static AuthView.Host host(Vault vault) {
        return new AuthView.Host() {
            @Override
            public Vault useServer(String serverUrl) {
                return vault;
            }

            @Override
            public void onUnlocked(Vault unlocked) {
            }
        };
    }

    private void renderNode(Path out, int w, int h, Supplier<Parent> build) throws Exception {
        runFx(() -> {
            Parent root = build.get();
            Scene scene = new Scene(root, w, h, Color.web("#16130F"));
            Theme.apply(scene);
            root.applyCss();
            root.layout();
            writePng(scene.snapshot(null), out);
        });
    }

    private void renderWorkspace(Path out, int w, int h, DesktopConfig cfg, Vault vault) throws Exception {
        java.util.concurrent.atomic.AtomicReference<Scene> sceneRef = new java.util.concurrent.atomic.AtomicReference<>();
        runFx(() -> {
            VaultView view = new VaultView(cfg, vault, () -> { });
            Scene scene = new Scene(view, w, h, Color.web("#16130F"));
            Theme.apply(scene);
            view.applyCss();
            view.layout();
            sceneRef.set(scene);
        });
        // Let the async notes() load and the most-recent note open in the editor.
        Thread.sleep(1500);
        runFx(() -> {
            Scene scene = sceneRef.get();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            writePng(scene.snapshot(null), out);
        });
    }

    private static void startFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
            latch.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyStarted) {
            // toolkit already up (another test in the JVM) — fine.
        }
    }

    private static void runFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX action timed out");
        }
        if (error.get() != null) {
            throw new RuntimeException("FX render failed", error.get());
        }
    }

    private static void writePng(WritableImage img, Path out) {
        try {
            int w = (int) img.getWidth();
            int h = (int) img.getHeight();
            BufferedImage bimg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            PixelReader reader = img.getPixelReader();
            int[] buffer = new int[w * h];
            reader.getPixels(0, 0, w, h, WritablePixelFormat.getIntArgbInstance(), buffer, 0, w);
            bimg.setRGB(0, 0, w, h, buffer, 0, w);
            ImageIO.write(bimg, "png", out.toFile());
        } catch (Exception e) {
            throw new RuntimeException("could not write " + out, e);
        }
    }
}
