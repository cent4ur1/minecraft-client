package keystrokesmod.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CapeManager {
    private static final long MAX_CAPE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_CAPE_DIMENSION = 8192;
    private static final BundledCape[] BUNDLED_CAPES = {
            new BundledCape("Anime", "anime.png"),
            new BundledCape("Aqua", "rvn_aqua.png"),
            new BundledCape("Green", "rvn_green.png"),
            new BundledCape("Purple", "rvn_purple.png"),
            new BundledCape("Red", "rvn_red.png"),
            new BundledCape("White", "rvn_white.png"),
            new BundledCape("Yellow", "rvn_yellow.png")
    };
    private static final File CAPE_DIRECTORY = new File(
            new File(Minecraft.getMinecraft().mcDataDir, "keystrokes"), "capes"
    );
    private static volatile List<ResourceLocation> capeTextures = Collections.emptyList();
    private static volatile List<ResourceLocation> customCapeTextures = Collections.emptyList();
    private static volatile String[] capeOptions = new String[]{ "None" };

    private CapeManager() {
    }

    public static File getCapeDirectory() {
        ensureCapeDirectory();
        return CAPE_DIRECTORY;
    }

    public static boolean openCapeDirectory() {
        ensureCapeDirectory();
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop.getDesktop().open(CAPE_DIRECTORY);
            return true;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    public static String[] getCapeOptions() {
        return capeOptions.clone();
    }

    public static ResourceLocation getCape(int optionIndex) {
        List<ResourceLocation> currentTextures = capeTextures;
        int textureIndex = optionIndex - 1;
        if (textureIndex < 0 || textureIndex >= currentTextures.size()) {
            return null;
        }
        return currentTextures.get(textureIndex);
    }

    public static synchronized ReloadResult reloadCustomCapes() {
        ensureCapeDirectory();
        deleteOldCustomTextures();

        List<String> optionNames = new ArrayList<String>();
        List<ResourceLocation> textures = new ArrayList<ResourceLocation>();
        List<ResourceLocation> dynamicTextures = new ArrayList<ResourceLocation>();
        optionNames.add("None");
        for (BundledCape cape : BUNDLED_CAPES) {
            optionNames.add(cape.displayName);
            textures.add(new ResourceLocation("keystrokesmod", "textures/capes/" + cape.fileName));
        }

        int loaded = 0;
        int failed = 0;
        File[] files = CAPE_DIRECTORY.listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (file.length() <= 0 || file.length() > MAX_CAPE_BYTES) {
                    failed++;
                    continue;
                }

                try {
                    BufferedImage image = ImageIO.read(file);
                    if (!isValidCape(image)) {
                        failed++;
                        continue;
                    }

                    String displayName = makeCustomDisplayName(file, optionNames);
                    ResourceLocation texture = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation(
                            "custom_cape_" + loaded, new DynamicTexture(image)
                    );
                    optionNames.add(displayName);
                    textures.add(texture);
                    dynamicTextures.add(texture);
                    loaded++;
                }
                catch (Throwable ignored) {
                    failed++;
                }
            }
        }

        capeTextures = Collections.unmodifiableList(textures);
        customCapeTextures = Collections.unmodifiableList(dynamicTextures);
        capeOptions = optionNames.toArray(new String[optionNames.size()]);
        return new ReloadResult(loaded, failed);
    }

    private static boolean isValidCape(BufferedImage image) {
        return image != null
                && image.getWidth() > 0
                && image.getHeight() > 0
                && image.getWidth() <= MAX_CAPE_DIMENSION
                && image.getHeight() <= MAX_CAPE_DIMENSION
                && image.getWidth() == image.getHeight() * 2;
    }

    private static String makeCustomDisplayName(File file, List<String> existingNames) {
        String fileName = file.getName();
        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String displayName = baseName;
        if (!containsIgnoreCase(existingNames, displayName)) {
            return displayName;
        }

        displayName = fileName;
        int suffix = 2;
        while (containsIgnoreCase(existingNames, displayName)) {
            displayName = fileName + " (" + suffix++ + ")";
        }
        return displayName;
    }

    private static boolean containsIgnoreCase(List<String> values, String requestedValue) {
        for (String value : values) {
            if (value.equalsIgnoreCase(requestedValue)) {
                return true;
            }
        }
        return false;
    }

    private static void deleteOldCustomTextures() {
        for (ResourceLocation texture : customCapeTextures) {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(texture);
        }
        customCapeTextures = Collections.emptyList();
    }

    private static void ensureCapeDirectory() {
        if (!CAPE_DIRECTORY.exists()) {
            CAPE_DIRECTORY.mkdirs();
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

    private static final class BundledCape {
        private final String displayName;
        private final String fileName;

        private BundledCape(String displayName, String fileName) {
            this.displayName = displayName;
            this.fileName = fileName;
        }
    }
}
