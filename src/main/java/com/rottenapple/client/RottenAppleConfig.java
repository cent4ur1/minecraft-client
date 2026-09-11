package com.rottenapple.client;

/**
 * Minimal in-memory settings store.
 * Kept intentionally small for v1. Persisted across GUI opens
 * via static fields (no disk I/O yet).
 */
public final class RottenAppleConfig {

    private RottenAppleConfig() {}

    public static boolean toggleSprint = false;
    public static boolean fullbright = false;
    public static boolean showFps = true;

    /** Window background opacity, 0.4 - 1.0 */
    public static float menuOpacity = 0.92f;

    /** Accent color (RGB, no alpha) used across the menu. */
    public static int accentRgb = 0xE5484D;

    public static void clamp() {
        if (menuOpacity < 0.4f) menuOpacity = 0.4f;
        if (menuOpacity > 1.0f) menuOpacity = 1.0f;
    }
}
