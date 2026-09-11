package com.rottenapple.module;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Registry for the ported Raven modules. Absent Raven modules stay null (guarded at use sites). */
public final class ModuleManager {
    private ModuleManager() {
    }

    public static final List<Module> modules = new ArrayList<Module>();
    private static final Map<Class<?>, Module> byClass = new HashMap<Class<?>, Module>();

    // Cross-module references used by ports. Raven siblings we did not port stay null.
    public static Module killAura = null;
    public static Module bedAura = null;
    public static Module antiKnockback = null;
    public static Module relationships = null;
    public static com.rottenapple.module.impl.player.FastPlace fastPlace = null;

    private static volatile boolean ready;

    public static void init() {
        if (ready) return;
        ready = true;
        add(new com.rottenapple.module.impl.combat.AutoClicker());
        add(new com.rottenapple.module.impl.combat.JumpReset());
        add(new com.rottenapple.module.impl.combat.Velocity());
        add(new com.rottenapple.module.impl.combat.Autoblock());
        add(new com.rottenapple.module.impl.player.AutoTool());
        add(new com.rottenapple.module.impl.player.AutoBlockin());
        add(new com.rottenapple.module.impl.player.BridgeAssist());
        add(fastPlace = new com.rottenapple.module.impl.player.FastPlace());
        add(new com.rottenapple.module.impl.render.Nametags());
        for (Module m : modules) {
            try {
                m.guiUpdate();
            } catch (Throwable ignored) {
            }
        }
        ConfigManager.load();
    }

    private static void add(Module m) {
        modules.add(m);
        byClass.put(m.getClass(), m);
    }

    @SuppressWarnings("unchecked")
    public static <T extends Module> T get(Class<T> c) {
        return (T) byClass.get(c);
    }

    public static void handleKeybinds() {
        for (Module m : modules) {
            try {
                m.onKeyBind();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void tickAll() {
        try {
            com.rottenapple.util.AntiBotUtil.tick(
                    com.rottenapple.bridge.MC.players(com.rottenapple.bridge.MC.world()));
        } catch (Throwable ignored) {
        }
        for (Module m : modules) {
            if (!m.isEnabled()) continue;
            try {
                m.onTick();
            } catch (Throwable t) {
                com.rottenapple.agent.RottenAppleAgent.status("module " + m.getName() + " tick failed: " + t);
                try {
                    m.disable();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Called after toggles/setting changes (throttled inside ConfigManager). */
    public static void saveSoon() {
        try {
            ConfigManager.save();
        } catch (Throwable ignored) {
        }
    }
}
