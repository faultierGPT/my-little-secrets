package app.mls.desktop.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;

/**
 * The design system's line icons, rebuilt as JavaFX shapes from the SAME 24×24 path data as
 * {@code design/icons/*.svg} (stroke 1.5, round caps/joins, no fill, {@code currentColor}). JavaFX
 * has no built-in SVG rasterizer, so embedding the geometry here keeps the desktop visually identical
 * to the shared icon set with zero extra dependencies.
 */
public final class Icons {

    private Icons() {
    }

    public static Node icon(String name, double size, Color color) {
        Shape[] parts = parts(name);
        for (Shape s : parts) {
            s.setFill(null);
            s.setStroke(color);
            s.setStrokeWidth(1.5);
            s.setStrokeLineCap(StrokeLineCap.ROUND);
            s.setStrokeLineJoin(StrokeLineJoin.ROUND);
        }
        Group g = new Group(parts);
        double scale = size / 24.0;
        g.getTransforms().add(new Scale(scale, scale));
        g.setManaged(true);
        return g;
    }

    public static Node icon(String name, double size) {
        return icon(name, size, Theme.TEXT_PRIMARY);
    }

    private static Shape[] parts(String name) {
        return switch (name) {
            case "note" -> new Shape[]{path("M6 3h8l4 4v14H6z"), path("M14 3v4h4"), path("M9 8h2M9 12h6M9 16h6")};
            case "plus" -> new Shape[]{path("M12 5v14M5 12h14")};
            case "search" -> new Shape[]{circle(11, 11, 6), path("M20 20l-3.8-3.8")};
            case "sync" -> new Shape[]{
                    path("M20 11a8 8 0 0 0-13.7-5L4 8"), path("M4 4v4h4"),
                    path("M4 13a8 8 0 0 0 13.7 5L20 16"), path("M20 20v-4h-4")};
            case "lock" -> new Shape[]{rect(5, 11, 14, 9, 2), path("M8 11V8a4 4 0 0 1 8 0v3"), circle(12, 15.5, 1.25)};
            case "unlock" -> new Shape[]{rect(5, 11, 14, 9, 2), path("M8 11V8a4 4 0 0 1 7.7-1.6"), circle(12, 15.5, 1.25)};
            case "trash" -> new Shape[]{
                    path("M5 7h14"), path("M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"),
                    path("M7 7l1 13a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1l1-13"), path("M10 11v6M14 11v6")};
            case "copy" -> new Shape[]{rect(8, 8, 12, 12, 2), path("M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2")};
            case "key" -> new Shape[]{circle(8, 12, 4), path("M12 12h9"), path("M17 12v3M20 12v2.5")};
            case "check" -> new Shape[]{path("M5 12.5l4.5 4.5L19 7")};
            case "close" -> new Shape[]{path("M6 6l12 12M18 6L6 18")};
            case "tag" -> new Shape[]{
                    path("M4 12.5V5a1 1 0 0 1 1-1h7.5L20 11.5a1.5 1.5 0 0 1 0 2L13.5 20a1.5 1.5 0 0 1-2 0L4 12.5z"),
                    circle(8.5, 8.5, 1.25)};
            case "shield" -> new Shape[]{path("M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z"), path("M9.5 12l2 2 3.5-4")};
            case "eye" -> new Shape[]{path("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"), circle(12, 12, 3)};
            case "eye-off" -> new Shape[]{
                    path("M3 3l18 18"),
                    path("M10.6 6.1A9.7 9.7 0 0 1 12 6c6 0 9.5 6 9.5 6a16 16 0 0 1-2.6 3.3"),
                    path("M6.5 7.6A15.6 15.6 0 0 0 2.5 12s3.5 6 9.5 6a9.6 9.6 0 0 0 4-.9"),
                    path("M9.9 9.9a3 3 0 0 0 4.2 4.2")};
            case "account" -> new Shape[]{circle(12, 8, 4), path("M5 20c0-3.6 3.1-6 7-6s7 2.4 7 6")};
            case "settings" -> new Shape[]{path("M4 7h8M16 7h4"), circle(14, 7, 2), path("M4 17h4M12 17h8"), circle(10, 17, 2)};
            case "chevron-right" -> new Shape[]{path("M9 6l6 6-6 6")};
            default -> throw new IllegalArgumentException("unknown icon: " + name);
        };
    }

    private static SVGPath path(String d) {
        SVGPath p = new SVGPath();
        p.setContent(d);
        return p;
    }

    private static Circle circle(double cx, double cy, double r) {
        return new Circle(cx, cy, r);
    }

    private static Rectangle rect(double x, double y, double w, double h, double rx) {
        Rectangle rect = new Rectangle(x, y, w, h);
        rect.setArcWidth(rx * 2);
        rect.setArcHeight(rx * 2);
        return rect;
    }
}
