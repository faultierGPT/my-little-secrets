package app.mls.desktop.design;

// GENERATED FILE — do not edit by hand.
// Source of truth: design/src/main/kotlin/app/mls/design/Tokens.kt
// Regenerate:      ./gradlew :design:run

/** my-little-secrets design tokens v1. Hex colors are sRGB #RRGGBB[AA]. */
public final class MlsTokens {
    private MlsTokens() {}

    /** App background — warm near-black. */
    public static final String BG_BASE = "#16130F";
    /** Raised surfaces: cards, sheets, the editor pane. */
    public static final String BG_ELEVATED = "#1E1A15";
    /** Menus/popovers — one step above elevated. */
    public static final String BG_OVERLAY = "#231E18";
    /** 1px hairline separators and input borders. */
    public static final String BORDER_HAIRLINE = "#2A251E";
    /** Primary reading/title text on dark surfaces. */
    public static final String TEXT_PRIMARY = "#EDE6DA";
    /** Secondary text: metadata, captions, placeholders. */
    public static final String TEXT_SECONDARY = "#A39A8B";
    /** Disabled text — secondary at 38% alpha. */
    public static final String TEXT_DISABLED = "#A39A8B61";
    /** The single brand accent — muted amber. Interactive emphasis. */
    public static final String ACCENT = "#C9A26B";
    /** Text/icon drawn ON an amber fill — the base surface, for contrast. */
    public static final String ACCENT_ON = "#16130F";
    /** Amber wash for selected rows/chips — accent at ~12% alpha. */
    public static final String ACCENT_SUBTLE = "#C9A26B1F";
    /** Hover overlay — primary text at ~6% alpha. */
    public static final String STATE_HOVER = "#EDE6DA0F";
    /** Pressed overlay — primary text at ~10% alpha. */
    public static final String STATE_PRESSED = "#EDE6DA1A";
    /** Keyboard-focus ring — the accent, drawn at 2px. */
    public static final String FOCUS_RING = "#C9A26B";
    /** Destructive-action signal (delete). A muted terracotta status color, not a second accent. */
    public static final String SEMANTIC_DANGER = "#C16A52";
    /** Text/icon on a danger fill. */
    public static final String SEMANTIC_DANGER_ON = "#16130F";
    /** Modal scrim — near-black at ~70% alpha. */
    public static final String SCRIM = "#0B0907B3";

    public static final String[] SERIF_FAMILIES = { "Spectral", "Newsreader", "Source Serif 4", "Georgia", "serif" };
    public static final String[] GROTESQUE_FAMILIES = { "Geist", "IBM Plex Sans", "Inter", "system-ui", "sans-serif" };

    public static final int SPACE_0 = 0;
    public static final int SPACE_1 = 2;
    public static final int SPACE_2 = 4;
    public static final int SPACE_3 = 8;
    public static final int SPACE_4 = 12;
    public static final int SPACE_5 = 16;
    public static final int SPACE_6 = 20;
    public static final int SPACE_7 = 24;
    public static final int SPACE_8 = 32;
    public static final int SPACE_9 = 40;
    public static final int SPACE_10 = 48;
    public static final int SPACE_11 = 64;

    public static final int RADIUS_SM = 6;
    public static final int RADIUS_MD = 10;
    public static final int RADIUS_LG = 14;
    public static final int RADIUS_PILL = 999;

    /** Hairline border/divider width in dp. */
    public static final int STROKE_HAIRLINE = 1;
    /** Keyboard-focus ring width in dp. */
    public static final int STROKE_FOCUS = 2;

    public static final int MOTION_FAST_MS = 120;
    public static final int MOTION_BASE_MS = 200;
    public static final int MOTION_SLOW_MS = 320;
}
