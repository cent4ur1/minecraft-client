package com.rottenapple.util;

import com.rottenapple.bridge.MC;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bot detection ported from Raven's AntiBot (minus its Forge join event;
 * first-seen tracking is done per poll instead).
 * Disabled by default, mirroring a fresh Raven install (module off).
 */
public final class AntiBotUtil {
    private AntiBotUtil() {
    }

    /** Mirrors the AntiBot module being off in a fresh Raven install. */
    public static volatile boolean enabled = false;

    /** Raven AntiBot defaults (Delay/Pit spawn disabled, tab list off). */
    public static volatile double delaySeconds = -1;
    public static volatile double pitSpawnY = -1;
    public static volatile boolean tabListCheck = false;

    private static final Map<Object, Long> firstSeen =
            new IdentityHashMap<Object, Long>();

    /** Call each poll with the current player list; tracks join times. */
    public static void tick(List<Object> players) {
        try {
            if (!enabled || delaySeconds == -1 || players == null) {
                if (firstSeen.size() > 200) {
                    firstSeen.clear();
                }
                return;
            }
            long now = System.currentTimeMillis();
            long keepMs = (long) (delaySeconds * 1000.0);
            for (Object p : players) {
                if (p != null && !firstSeen.containsKey(p)) {
                    firstSeen.put(p, Long.valueOf(now));
                }
            }
            java.util.Iterator<Map.Entry<Object, Long>> it = firstSeen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Object, Long> e = it.next();
                if (e.getValue().longValue() < now - keepMs) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void clear() {
        try {
            firstSeen.clear();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isBot(Object entity) {
        try {
            if (!enabled) return false;
            if (entity == null) return false;
            if (!MC.isPlayer(entity)) return true;
            if (MC.isDead(entity)) return true;
            String name = MC.entName(entity);
            if (name != null && com.rottenapple.util.RavenUtil.removeFormatCodes(name).trim().isEmpty()) return true;
            if (tabListCheck && !MC.tabNames().contains(name)) return true;
            if (MC.getHealth(entity) != 20.0f && name != null && name.startsWith("§c")) return true;
            if (pitSpawnY != -1 && MC.posY(entity) >= pitSpawnY && MC.posY(entity) <= 130
                    && MC.getDistance(entity, 0, 114, 0) <= 25 && isHypixelPit()) {
                return true;
            }
            if (MC.maxHurtTime(entity) == 0) {
                if (MC.getHealth(entity) == 20.0f) {
                    String unf = MC.displayNameUnf(entity);
                    if (unf.length() == 10 && unf.charAt(0) != '§') return true;
                    if (unf.length() == 12 && MC.isPlayerSleeping(entity) && unf.charAt(0) == '§') return true;
                    if (unf.length() >= 7 && unf.charAt(2) == '[' && unf.charAt(3) == 'N' && unf.charAt(6) == ']') return true;
                    if (name != null && name.contains(" ")) return true;
                } else if (MC.isInvisible(entity)) {
                    String unf = MC.displayNameUnf(entity);
                    if (unf.length() >= 3 && unf.charAt(0) == '§' && unf.charAt(1) == 'c') return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isHypixelPit() {
        try {
            String ip = MC.serverIP();
            boolean hypixel = !MC.isSingleplayer() && ip != null && ip.contains("hypixel.net");
            if (!hypixel) return false;
            String title = MC.sidebarTitle();
            return title != null && title.contains("THE HYPIXEL PIT");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
