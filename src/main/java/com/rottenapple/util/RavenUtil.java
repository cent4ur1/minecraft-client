package com.rottenapple.util;

/** Pure-java ports of Raven's math/string helpers (no game imports). */
public final class RavenUtil {
    private RavenUtil() {
    }

    public static double round(double val, int decimalPlaces) {
        if (decimalPlaces == 0) {
            return (double) Math.round(val);
        }
        double p = Math.pow(10.0D, decimalPlaces);
        return (double) Math.round(val * p) / p;
    }

    public static double randomizeDouble(double min, double max) {
        return min + (max - min) * Math.random();
    }

    public static double wrapAngleTo180(double v) {
        v %= 360.0;
        if (v >= 180.0) v -= 360.0;
        if (v < -180.0) v += 360.0;
        return v;
    }

    public static float wrapAngleTo180(float v) {
        return (float) wrapAngleTo180((double) v);
    }

    /** Mirrors Utils.getCustomDirection. */
    public static float getCustomDirection(float yaw, float moveForward, float moveStrafe) {
        float forward = 1.0f;
        if (moveForward < 0.0f) {
            yaw += 180.0f;
            forward = -0.5f;
        } else if (moveForward > 0.0f) {
            forward = 0.5f;
        }
        if (moveStrafe > 0.0f) {
            yaw -= 90.0f * forward;
        } else if (moveStrafe < 0.0f) {
            yaw += 90.0f * forward;
        }
        return yaw * 0.017453292f;
    }

    /** Mirrors RotationUtils.deltaAngle. */
    public static float deltaAngle(double n, double n2) {
        return (float) (Math.atan2(n, n2) * 57.295780181884766 * -1.0);
    }

    /** Mirrors RotationUtils.angle (needs player pos passed in). */
    public static float angle(double px, double pz, double n, double n2) {
        return (float) (Math.atan2(n - px, n2 - pz) * 57.295780181884766 * -1.0);
    }

    /** Mirrors Utils.inFov(origin, fov, targetYaw). */
    public static boolean inFov(float origin, float fov, float targetYaw) {
        fov *= 0.5F;
        double w = wrapAngleTo180((origin - targetYaw) % 360.0f);
        if (w > 0.0) {
            return w < fov;
        }
        return w > -fov;
    }

    public static String getFirstColorCode(String input) {
        if (input == null || input.length() < 2) {
            return "";
        }
        for (int i = 0; i < input.length() - 1; i++) {
            if (input.charAt(i) == '§') {
                char c = input.charAt(i + 1);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                    return "§" + c;
                }
            }
        }
        return "";
    }

    public static String stripFormattingCodes(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String removeFormatCodes(String str) {
        if (str == null) return "";
        return str.replace("§k", "").replace("§l", "").replace("§m", "")
                .replace("§n", "").replace("§o", "").replace("§r", "");
    }

    public static int mergeAlpha(int color, int alpha) {
        return (color & 0xFFFFFF) | alpha << 24;
    }

    public static String fastOneDecimal(float value) {
        int whole = (int) value;
        if (value == whole) {
            return String.valueOf(whole);
        }
        int tenths = Math.round(value * 10.0F);
        int intPart = tenths / 10;
        int fracPart = Math.abs(tenths % 10);
        return intPart + "." + fracPart;
    }

    public static int msToTicks(double ms) {
        if (ms <= 0.0) return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    // Nametags enchant tables (identical to Raven's).
    public static final int[] ARMOR_ENCHANT_IDS = {0, 7, 34};
    public static final String[] ARMOR_ENCHANT_ABBR = {"P", "T", "U"};
    public static final int[] SWORD_ENCHANT_IDS = {16, 20, 19};
    public static final String[] SWORD_ENCHANT_ABBR = {"S", "F", "K"};
    public static final int[] BOW_ENCHANT_IDS = {48, 49, 50};
    public static final String[] BOW_ENCHANT_ABBR = {"Pw", "Pu", "Fl"};
    public static final int[] TOOL_ENCHANT_IDS = {32, 35, 34};
    public static final String[] TOOL_ENCHANT_ABBR = {"E", "Fo", "U"};
    public static final int[] MISC_ENCHANT_IDS = {19};
    public static final String[] MISC_ENCHANT_ABBR = {"K"};

    public static int colorForEnchantLevel(int level) {
        if (level <= 5) {
            if (level == 1) return 0xFFFFFF;
            if (level == 2) return 0x55FFFF;
            if (level == 3) return 0x00AAAA;
            if (level == 4) return 0xAA00AA;
            if (level == 5) return 0xFFAA00;
        }
        return 0xFF55FF;
    }

    /** Mirrors Utils.getLookVec (pure math). */
    public static double[] lookVec(float yaw, float pitch) {
        double f = Math.cos(-yaw * 0.017453292 - Math.PI);
        double f1 = Math.sin(-yaw * 0.017453292 - Math.PI);
        double f2 = -Math.cos(-pitch * 0.017453292);
        double f3 = Math.sin(-pitch * 0.017453292);
        return new double[]{f1 * f2, f3, f * f2};
    }
}
