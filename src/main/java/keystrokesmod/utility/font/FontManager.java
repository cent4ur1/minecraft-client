package keystrokesmod.utility.font;

import keystrokesmod.module.setting.impl.SliderSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.awt.Desktop;
import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class FontManager {
    private static final String MINECRAFT = "Minecraft";
    private static final String RESOURCE_ROOT = "/assets/keystrokesmod/fonts/";
    private static final long MAX_CUSTOM_FONT_BYTES = 32L * 1024L * 1024L;
    /** Large enough that HUD font-size drags + clickgui + nametags do not evict active renderers every frame. */
    private static final int MAX_CACHED_RENDERERS = 512;
    private static final float DEFAULT_HUD_FONT_SIZE = 10.0f;
    private static final float DEFAULT_CLICK_GUI_HEADER_HEIGHT = 9.0f;
    private static final float DEFAULT_CLICK_GUI_SETTING_HEIGHT = 9.0f;
    private static final float DEFAULT_NAMETAG_FONT_SIZE = 9.0f;
    private static final FontDefinition[] BUNDLED_FONTS = {
            FontDefinition.bundled("Sf-Bold", "Sf-Bold.ttf"),
            FontDefinition.bundled("Sf-Regular", "Sf-Regular.ttf"),
            FontDefinition.bundled("Sf-Ui", "Sf-Ui.ttf")
    };
    private static final File FONT_DIRECTORY = new File(
            new File(Minecraft.getMinecraft().mcDataDir, "keystrokes"), "fonts"
    );
    private static final List<SliderSetting> FONT_SETTINGS = Collections.synchronizedList(new ArrayList<SliderSetting>());
    private static final Map<String, Font> BASE_FONT_CACHE = new ConcurrentHashMap<String, Font>();
    private static final Map<String, RavenFontRenderer> FONT_CACHE = new LinkedHashMap<String, RavenFontRenderer>(16, 0.75f, true);
    private static volatile Map<String, FontDefinition> fontDefinitions = Collections.emptyMap();
    private static volatile String[] hudFontOptions = new String[]{ MINECRAFT };

    static {
        reloadCustomFonts();
    }

    private FontManager() {
    }

    public static String getDefaultFontName() {
        return MINECRAFT;
    }

    public static File getFontDirectory() {
        ensureFontDirectory();
        return FONT_DIRECTORY;
    }

    public static boolean openFontDirectory() {
        ensureFontDirectory();
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop.getDesktop().open(FONT_DIRECTORY);
            return true;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    public static SliderSetting createFontSetting(String settingName) {
        SliderSetting setting = new SliderSetting(settingName, 0, getHudFontOptions());
        FONT_SETTINGS.add(setting);
        return setting;
    }

    public static String[] getHudFontOptions() {
        return hudFontOptions.clone();
    }

    public static synchronized ReloadResult reloadCustomFonts() {
        ensureFontDirectory();

        LinkedHashMap<String, FontDefinition> rebuiltDefinitions = new LinkedHashMap<String, FontDefinition>();
        LinkedHashMap<String, Font> validatedCustomFonts = new LinkedHashMap<String, Font>();
        for (FontDefinition bundledFont : BUNDLED_FONTS) {
            rebuiltDefinitions.put(bundledFont.displayName, bundledFont);
        }

        int loaded = 0;
        int failed = 0;
        File[] files = FONT_DIRECTORY.listFiles(file -> file.isFile() && isSupportedFontFile(file.getName()));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (file.length() <= 0 || file.length() > MAX_CUSTOM_FONT_BYTES) {
                    failed++;
                    continue;
                }

                Font baseFont = loadCustomFont(file);
                if (baseFont == null) {
                    failed++;
                    continue;
                }

                String displayName = makeCustomDisplayName(file, rebuiltDefinitions);
                FontDefinition definition = FontDefinition.custom(displayName, file);
                rebuiltDefinitions.put(displayName, definition);
                validatedCustomFonts.put(definition.cacheKey, baseFont);
                loaded++;
            }
        }

        clearRendererCache();
        BASE_FONT_CACHE.clear();
        BASE_FONT_CACHE.putAll(validatedCustomFonts);
        fontDefinitions = Collections.unmodifiableMap(rebuiltDefinitions);

        String[] options = new String[rebuiltDefinitions.size() + 1];
        options[0] = MINECRAFT;
        int optionIndex = 1;
        for (String displayName : rebuiltDefinitions.keySet()) {
            options[optionIndex++] = displayName;
        }
        hudFontOptions = options;

        synchronized (FONT_SETTINGS) {
            for (SliderSetting setting : FONT_SETTINGS) {
                setting.setOptions(options);
            }
        }
        return new ReloadResult(loaded, failed);
    }

    public static RavenFontRenderer getHudRenderer(String family, float scale) {
        float safeScale = Math.max(0.5f, Math.min(2.0f, scale));
        return getRenderer(family, DEFAULT_HUD_FONT_SIZE * safeScale);
    }

    public static RavenFontRenderer getClickGuiHeaderRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_HEADER_HEIGHT);
    }

    public static RavenFontRenderer getClickGuiSettingRenderer(String family) {
        return getRendererForPixelHeight(family, DEFAULT_CLICK_GUI_SETTING_HEIGHT);
    }

    public static RavenFontRenderer getNametagRenderer(String family) {
        return getRenderer(family, DEFAULT_NAMETAG_FONT_SIZE);
    }

    private static RavenFontRenderer getRenderer(String family, float fontSize) {
        float safeFontSize = Math.max(1.0f, fontSize);
        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeFontSize);
        }

        FontDefinition fontDefinition = fontDefinitions.get(family);
        if (fontDefinition == null) {
            return getMinecraftRenderer(safeFontSize);
        }

        String key = family + "#" + quantizeForCacheKey(safeFontSize) + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
                Font baseFont = getBaseFont(fontDefinition);
                if (baseFont == null) {
                    return getMinecraftRenderer(safeFontSize);
                }
                return new GlyphFontRenderer(baseFont.deriveFont(safeFontSize), true);
            }
        });
    }

    private static RavenFontRenderer getRendererForPixelHeight(String family, float targetHeight) {
        float safeTargetHeight = Math.max(1.0f, targetHeight);
        if (family == null || isMinecraftFont(family)) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        FontDefinition fontDefinition = fontDefinitions.get(family);
        if (fontDefinition == null) {
            return getMinecraftRenderer(safeTargetHeight);
        }

        String key = family + "#height#" + quantizeForCacheKey(safeTargetHeight) + "#" + getUiScale();
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
                Font baseFont = getBaseFont(fontDefinition);
                if (baseFont == null) {
                    return getMinecraftRenderer(safeTargetHeight);
                }
                return createHeightMatchedRenderer(baseFont, safeTargetHeight);
            }
        });
    }

    private static RavenFontRenderer createHeightMatchedRenderer(Font baseFont, float targetHeight) {
        float derivedSize = targetHeight;
        GlyphFontRenderer renderer = new GlyphFontRenderer(baseFont.deriveFont(derivedSize), true);

        for (int i = 0; i < 2; i++) {
            float measuredHeight = Math.max(1.0f, renderer.getFontHeight());
            float difference = Math.abs(measuredHeight - targetHeight);
            if (difference <= 0.5f) {
                break;
            }

            GlyphFontRenderer previousRenderer = renderer;
            derivedSize = Math.max(1.0f, derivedSize * (targetHeight / measuredHeight));
            renderer = new GlyphFontRenderer(baseFont.deriveFont(derivedSize), true);
            previousRenderer.destroy();
        }
        return renderer;
    }

    private static RavenFontRenderer getMinecraftRenderer(float fontSize) {
        float vanillaHeight = Math.max(1.0f, Minecraft.getMinecraft().fontRendererObj.FONT_HEIGHT);
        float scale = Math.max(0.5f, Math.min(2.0f, fontSize / vanillaHeight));
        String key = MINECRAFT + "#" + quantizeForCacheKey(scale);
        return getCachedRenderer(key, new Supplier<RavenFontRenderer>() {
            @Override
            public RavenFontRenderer get() {
                return new MinecraftFontAdapter(Minecraft.getMinecraft().fontRendererObj, scale);
            }
        });
    }

    public static boolean isMinecraftFont(String family) {
        return family == null || MINECRAFT.equalsIgnoreCase(family);
    }

    private static Font getBaseFont(FontDefinition definition) {
        Font cached = BASE_FONT_CACHE.get(definition.cacheKey);
        if (cached != null) {
            return cached;
        }

        Font loaded = definition.customFile == null
                ? loadBundledFont(definition.bundledFileName)
                : loadCustomFont(definition.customFile);
        if (loaded != null) {
            Font existing = BASE_FONT_CACHE.putIfAbsent(definition.cacheKey, loaded);
            return existing == null ? loaded : existing;
        }
        return null;
    }

    private static Font loadBundledFont(String fileName) {
        try (InputStream inputStream = FontManager.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
            return createFont(readFontData(inputStream));
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static Font loadCustomFont(File file) {
        try (InputStream inputStream = new FileInputStream(file)) {
            return createFont(readFontData(inputStream));
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static Font createFont(byte[] fontData) {
        if (fontData == null) {
            return null;
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(fontData));
        }
        catch (Exception ignored) {
            try {
                return Font.createFont(Font.TYPE1_FONT, new ByteArrayInputStream(fontData));
            }
            catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static byte[] readFontData(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return null;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static String makeCustomDisplayName(File file, Map<String, FontDefinition> definitions) {
        String fileName = file.getName();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String displayName = baseName;
        if (!definitions.containsKey(displayName)) {
            return displayName;
        }

        displayName = fileName;
        int suffix = 2;
        while (definitions.containsKey(displayName)) {
            displayName = fileName + " (" + suffix++ + ")";
        }
        return displayName;
    }

    private static boolean isSupportedFontFile(String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".ttf") || lowerName.endsWith(".otf");
    }

    private static void ensureFontDirectory() {
        if (!FONT_DIRECTORY.exists()) {
            FONT_DIRECTORY.mkdirs();
        }
    }

    private static int getUiScale() {
        try {
            return Math.max(1, new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor());
        }
        catch (Exception ignored) {
            return 1;
        }
    }

    /** Fewer unique keys when sliders move smoothly; avoids LRU evicting live glyph textures every frame. */
    private static float quantizeForCacheKey(float value) {
        return Math.round(value * 100.0f) / 100.0f;
    }

    private static synchronized RavenFontRenderer getCachedRenderer(String key, Supplier<RavenFontRenderer> rendererSupplier) {
        RavenFontRenderer renderer = FONT_CACHE.get(key);
        if (renderer != null) {
            return renderer;
        }

        renderer = rendererSupplier.get();
        FONT_CACHE.put(key, renderer);
        trimFontCache();
        return renderer;
    }

    private static synchronized void clearRendererCache() {
        for (RavenFontRenderer renderer : FONT_CACHE.values()) {
            if (renderer != null) {
                renderer.destroy();
            }
        }
        FONT_CACHE.clear();
    }

    private static void trimFontCache() {
        while (FONT_CACHE.size() > MAX_CACHED_RENDERERS) {
            Iterator<Map.Entry<String, RavenFontRenderer>> iterator = FONT_CACHE.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }

            Map.Entry<String, RavenFontRenderer> eldestEntry = iterator.next();
            iterator.remove();
            if (eldestEntry.getValue() != null) {
                eldestEntry.getValue().destroy();
            }
        }
    }

    public static final class ReloadResult {
        private final int loaded;
        private final int failed;

        private ReloadResult(int loaded, int failed) {
            this.loaded = loaded;
            this.failed = failed;
        }

        public int getLoaded() {
            return loaded;
        }

        public int getFailed() {
            return failed;
        }
    }

    private static final class FontDefinition {
        private final String displayName;
        private final String bundledFileName;
        private final File customFile;
        private final String cacheKey;

        private FontDefinition(String displayName, String bundledFileName, File customFile, String cacheKey) {
            this.displayName = displayName;
            this.bundledFileName = bundledFileName;
            this.customFile = customFile;
            this.cacheKey = cacheKey;
        }

        private static FontDefinition bundled(String displayName, String fileName) {
            return new FontDefinition(displayName, fileName, null, "bundled:" + fileName);
        }

        private static FontDefinition custom(String displayName, File file) {
            return new FontDefinition(displayName, null, file, "custom:" + file.getAbsolutePath());
        }
    }
}
