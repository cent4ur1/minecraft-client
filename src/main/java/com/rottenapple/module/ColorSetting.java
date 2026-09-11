package com.rottenapple.module;

/** RGB(A) color (mirrors Raven's ColorSetting). */
public class ColorSetting extends Setting {
    private int red;
    private int green;
    private int blue;
    private int alpha;
    private final boolean hasAlpha;
    public GroupSetting groupSetting;

    public ColorSetting(String name, int red, int green, int blue) {
        this(null, name, red, green, blue, 255, false);
    }

    public ColorSetting(String name, int red, int green, int blue, int alpha) {
        this(null, name, red, green, blue, alpha, true);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue) {
        this(groupSetting, name, red, green, blue, 255, false);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue, int alpha) {
        this(groupSetting, name, red, green, blue, alpha, true);
    }

    public ColorSetting(GroupSetting groupSetting, String name, int red, int green, int blue, int alpha, boolean hasAlpha) {
        super(name);
        this.groupSetting = groupSetting;
        this.red = clamp(red);
        this.green = clamp(green);
        this.blue = clamp(blue);
        this.alpha = clamp(alpha);
        this.hasAlpha = hasAlpha;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int getAlpha() {
        return alpha;
    }

    public void setAlpha(int alpha) {
        this.alpha = clamp(alpha);
    }

    public void setColor(int r, int g, int b) {
        this.red = clamp(r);
        this.green = clamp(g);
        this.blue = clamp(b);
    }

    public void setColor(int r, int g, int b, int a) {
        setColor(r, g, b);
        this.alpha = clamp(a);
    }

    /** ARGB packed int. */
    public int getColor() {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** RGB packed int (no alpha). */
    public int getRGB() {
        return (red << 16) | (green << 8) | blue;
    }

    public boolean hasAlpha() {
        return hasAlpha;
    }

    @Override
    public String getProfileKey() {
        return groupSetting == null ? getName() : groupSetting.getName() + "." + getName();
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
