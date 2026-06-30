package app.mls.desktop.ui;

import app.mls.desktop.design.MlsTokens;
import javafx.scene.Scene;
import javafx.scene.paint.Color;

import java.util.Objects;

/**
 * Loads the GENERATED JavaFX stylesheet (single source of truth in {@code :design}) and exposes the
 * handful of solid token colors needed in code (e.g. for SVG icon strokes). Everything else is
 * styled via CSS style-classes so the look stays driven by the design tokens, not hand-tuned here.
 */
public final class Theme {

    /** Classpath URL of the generated stylesheet (resources srcDir → /mls-theme.css). */
    public static final String STYLESHEET =
            Objects.requireNonNull(Theme.class.getResource("/mls-theme.css"),
                    "mls-theme.css missing from classpath (is :design generated?)").toExternalForm();

    public static final Color TEXT_PRIMARY = Color.web(MlsTokens.TEXT_PRIMARY);
    public static final Color TEXT_SECONDARY = Color.web(MlsTokens.TEXT_SECONDARY);
    public static final Color ACCENT = Color.web(MlsTokens.ACCENT);
    public static final Color ACCENT_ON = Color.web(MlsTokens.ACCENT_ON);
    public static final Color DANGER = Color.web(MlsTokens.SEMANTIC_DANGER);

    private Theme() {
    }

    /** Attach the stylesheet and tag the root so the {@code .mls-app} background applies. */
    public static void apply(Scene scene) {
        scene.getStylesheets().add(STYLESHEET);
        if (!scene.getRoot().getStyleClass().contains("mls-app")) {
            scene.getRoot().getStyleClass().add("mls-app");
        }
    }
}
