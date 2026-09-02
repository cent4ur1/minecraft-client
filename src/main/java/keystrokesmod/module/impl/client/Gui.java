package keystrokesmod.module.impl.client;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;

import java.awt.*;

public class Gui extends Module {
    public static SliderSetting guiScale;
    public static SliderSetting font;
    public static SliderSetting backgroundBlur;
    public static SliderSetting scrollSpeed;
    public static ButtonSetting hidePlayerModel;
    public static ButtonSetting darkBackground;
    public static ButtonSetting hideWatermark;
    public static ButtonSetting rainBowOutlines;
    public static ButtonSetting liteMode;
    public static ButtonSetting loadGuiPositions;

    public static ColorSetting enabledColor;
    public static ColorSetting disabledColor;

    public static final int UNSAVED_COLOR = new Color(114, 188, 250).getRGB();
    public static final int INVALID_COLOR = new Color(255, 80, 80).getRGB();
    public static final int INFO_COLOR = new Color(57, 146, 229, 255).getRGB();

    public Gui() {
        super("Gui", category.client, 54);
        this.liteModule = true;
        this.registerSetting(guiScale = new SliderSetting("Scale", "x", 1.0, 0.5, 2.0, 0.01, "Gui scale"));
        this.registerSetting(font = FontManager.createFontSetting("Font"));
        this.registerSetting(backgroundBlur = new SliderSetting("Background blur", "%", 0, 0, 100, 1));
        this.registerSetting(scrollSpeed = new SliderSetting("Scroll speed", 20, 2, 90, 1));
        this.registerSetting(darkBackground = new ButtonSetting("Dark background", true));
        this.registerSetting(hidePlayerModel = new ButtonSetting("Hide player model", false));
        this.registerSetting(hideWatermark = new ButtonSetting("Hide watermark", false));
        this.registerSetting(rainBowOutlines = new ButtonSetting("Rainbow outlines", true));
        this.registerSetting(liteMode = new ButtonSetting("Lite mode", false));
        this.registerSetting(loadGuiPositions = new ButtonSetting("Save category positions", false));
        this.registerSetting(new ButtonSetting("Reset positions", () -> Raven.clickGui.resetPositions()));
        this.registerSetting(new DescriptionSetting("Colors"));
        this.registerSetting(enabledColor = new ColorSetting("Enabled color", 24, 154, 255));
        this.registerSetting(disabledColor = new ColorSetting("Disabled color", 192, 192, 192));
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck() && mc.currentScreen != Raven.clickGui) {
            mc.displayGuiScreen(Raven.clickGui);
            Raven.clickGui.initMain();
        }

        this.disable();
    }

    public static String getSelectedFontName() {
        if (font == null) {
            return FontManager.getDefaultFontName();
        }

        int index = (int) Math.max(0, Math.min(font.getOptions().length - 1, font.getInput()));
        return font.getOptions()[index];
    }

    public static RavenFontRenderer getClickGuiHeaderFontRenderer() {
        return FontManager.getClickGuiHeaderRenderer(getSelectedFontName());
    }

    public static RavenFontRenderer getClickGuiSettingFontRenderer() {
        return FontManager.getClickGuiSettingRenderer(getSelectedFontName());
    }

    public static float getClickGuiScale() {
        if (guiScale == null) {
            return 1.0F;
        }

        return (float) Math.max(0.5D, Math.min(2.0D, guiScale.getInput()));
    }

    public static boolean isLiteModeEnabled() {
        return liteMode != null && liteMode.isToggled();
    }

    public static boolean shouldShowModule(Module module) {
        return module.moduleCategory() == category.profiles
                || module.moduleCategory() == category.scripts
                || !isLiteModeEnabled()
                || module.liteModule;
    }

    @Override
    public void guiButtonToggled(ButtonSetting setting) {
        if (setting == liteMode && Raven.clickGui != null) {
            Raven.clickGui.reloadModulesForCurrentMode();
        }
    }

}
