package com.rottenapple.bridge;

import com.rottenapple.client.RottenAppleConfig;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection-only bridge between the agent and the game.
 *
 * <p>Deliberately zero {@code net.minecraft.*} / {@code org.lwjgl.*} imports:
 * the agent jar stays dependency-free and unknown mappings degrade to
 * "skip that effect" instead of crashing. Everything here tolerates failure.
 * </p>
 */
public final class GameBridge {

    /** LWJGL2 {@code Keyboard.KEY_INSERT}. */
    public static final int KEY_INSERT = 210;
    /** LWJGL2 {@code Keyboard.KEY_P} (backup toggle bind). */
    public static final int KEY_P = 25;

    private static volatile ClassLoader gameLoader;
    private static volatile Class<?> minecraftClass;
    private static volatile Object minecraft;

    private static volatile Method getMinecraftMethod;
    private static volatile Field currentScreenField;
    private static volatile Field gameSettingsField;
    private static volatile Field gammaField;
    private static volatile Field forwardKeyField;
    private static volatile Field thePlayerField;

    private static volatile Method keyDownMethod;
    private static volatile Method lwjglIsKeyDown;
    private static volatile Method isSneakingMethod;
    private static volatile Method setSprintingMethod;
    private static volatile Method getFoodStatsMethod;
    private static volatile Method getFoodLevelMethod;
    private static volatile Method getDebugFpsMethod;

    private GameBridge() {}

    public static boolean isReady() {
        return minecraft != null;
    }

    /** Raw game classloader for module reflection (null until ready). */
    public static ClassLoader getGameLoader() {
        return gameLoader;
    }

    /** Loads a game/LWJGL class in the game's loader. Null on failure. */
    public static Class<?> loadGameClass(String name) {
        try {
            ClassLoader l = gameLoader;
            if (l == null) return null;
            return Class.forName(name, false, l);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Raw Minecraft singleton (may be null). Modules should prefer MC helpers. */
    public static Object getMinecraftInstance() {
        return minecraft;
    }

    /**
     * Blocks until the game entry class is loaded in the target JVM
     * (or timeout). Resolves all reflective handles on success.
     */
    public static boolean waitForGame(Instrumentation inst, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                Class<?> found = findMinecraftClass(inst);
                if (found != null) {
                    minecraftClass = found;
                    gameLoader = found.getClassLoader();
                    if (resolve(found) && refresh()) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // keep waiting
            }
            sleep(250);
        }
        return minecraft != null;
    }

    /** Actual game-entry class name once found (e.g. obfuscated name). */
    public static String getFoundClassName() {
        try {
            Class<?> c = minecraftClass;
            return c == null ? null : c.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Finds the game entry class. Exact MCP name first; falls back to
     * signature discovery (any class with {@code static Self getMinecraft()}),
     * so an obfuscated runtime is still detected (effects may degrade, but
     * the menu keeps working).
     */
    private static Class<?> findMinecraftClass(Instrumentation inst) {
        Class<?> fallback = null;
        Class<?>[] all;
        try {
            all = inst.getAllLoadedClasses();
        } catch (Throwable ignored) {
            return null;
        }
        for (Class<?> c : all) {
            try {
                if ("net.minecraft.client.Minecraft".equals(c.getName())) {
                    return c;
                }
                if (fallback == null && hasGetMinecraft(c)) {
                    fallback = c;
                }
            } catch (Throwable ignored) {
                // keep scanning
            }
        }
        return fallback;
    }

    private static boolean hasGetMinecraft(Class<?> c) {
        try {
            Method m = c.getDeclaredMethod("getMinecraft");
            return m.getParameterTypes().length == 0
                    && m.getReturnType() == c
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Re-reads the Minecraft singleton. Cheap; call once per poll. */
    public static boolean refresh() {
        try {
            if (getMinecraftMethod != null) {
                Object mc = getMinecraftMethod.invoke(null);
                if (mc != null) {
                    minecraft = mc;
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // keep last known instance
        }
        return minecraft != null;
    }

    /** True while the game has any GUI screen open (chat, inventory, ...). */
    public static boolean isGuiOpen() {
        try {
            Object mc = minecraft;
            if (mc == null || currentScreenField == null) return false;
            return currentScreenField.get(mc) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Polls LWJGL2 Keyboard state for INSERT or P. False if unknown. */
    public static boolean isToggleDown() {
        try {
            if (lwjglIsKeyDown == null) return false;
            return Boolean.TRUE.equals(lwjglIsKeyDown.invoke(null, Integer.valueOf(KEY_INSERT)))
                    || Boolean.TRUE.equals(lwjglIsKeyDown.invoke(null, Integer.valueOf(KEY_P)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Applies sprint + fullbright from {@link RottenAppleConfig}. */
    public static void applyEffects() {
        Object mc = minecraft;
        if (mc == null || gameSettingsField == null) return;
        try {
            Object settings = gameSettingsField.get(mc);
            if (settings == null) return;

            if (gammaField != null) {
                float g = gammaField.getFloat(settings);
                if (RottenAppleConfig.fullbright) {
                    if (g < 100.0f) gammaField.setFloat(settings, 100.0f);
                } else if (g > 1.0f) {
                    gammaField.setFloat(settings, 1.0f);
                }
            }

            if (RottenAppleConfig.toggleSprint
                    && forwardKeyField != null
                    && thePlayerField != null
                    && keyDownMethod != null
                    && setSprintingMethod != null
                    && !isGuiOpen()) {
                Object forward = forwardKeyField.get(settings);
                Object player = thePlayerField.get(mc);
                if (forward != null && player != null
                        && Boolean.TRUE.equals(keyDownMethod.invoke(forward))
                        && !isSneaking(player)
                        && foodOk(player)) {
                    setSprintingMethod.invoke(player, Boolean.TRUE);
                }
            }
        } catch (Throwable ignored) {
            // never let the poller die on a mapping surprise
        }
    }

    /** FPS string, or null when unavailable. */
    public static String getFps() {
        try {
            Object mc = minecraft;
            if (mc == null || getDebugFpsMethod == null) return null;
            Object r = getDebugFpsMethod.invoke(mc);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- internals ----------

    private static boolean resolve(Class<?> mcClass) {
        try {
            getMinecraftMethod = mcClass.getMethod("getMinecraft");
        } catch (Throwable ignored) {
            return false;
        }
        currentScreenField = field(mcClass, "currentScreen");
        gameSettingsField = field(mcClass, "gameSettings");
        thePlayerField = field(mcClass, "thePlayer");

        Class<?> settingsClass = type("net.minecraft.client.settings.GameSettings");
        if (settingsClass != null) {
            gammaField = field(settingsClass, "gammaSetting");
            forwardKeyField = field(settingsClass, "keyBindForward");
        }
        Class<?> kbClass = type("net.minecraft.client.settings.KeyBinding");
        keyDownMethod = method(kbClass, "isKeyDown");

        Class<?> playerClass = type("net.minecraft.entity.player.EntityPlayer");
        isSneakingMethod = method(playerClass, "isSneaking");
        setSprintingMethod = method(playerClass, "setSprinting", Boolean.TYPE);
        getFoodStatsMethod = method(playerClass, "getFoodStats");

        Class<?> foodClass = type("net.minecraft.util.FoodStats");
        getFoodLevelMethod = method(foodClass, "getFoodLevel");

        Class<?> lwjgl = type("org.lwjgl.input.Keyboard");
        lwjglIsKeyDown = method(lwjgl, "isKeyDown", Integer.TYPE);

        try {
            getDebugFpsMethod = mcClass.getMethod("getDebugFPS");
        } catch (Throwable ignored) {
            getDebugFpsMethod = null;
        }
        return true;
    }

    private static Class<?> type(String name) {
        try {
            ClassLoader l = gameLoader;
            if (l == null) return null;
            return Class.forName(name, false, l);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            if (c == null) return null;
            return c.getField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        try {
            if (c == null) return null;
            return c.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isSneaking(Object player) {
        try {
            if (isSneakingMethod == null) return false;
            return Boolean.TRUE.equals(isSneakingMethod.invoke(player));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean foodOk(Object player) {
        try {
            if (getFoodStatsMethod == null || getFoodLevelMethod == null) return true;
            Object food = getFoodStatsMethod.invoke(player);
            if (food == null) return true;
            Object level = getFoodLevelMethod.invoke(food);
            return !(level instanceof Number) || ((Number) level).intValue() > 6;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
