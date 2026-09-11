package com.rottenapple.module.impl.combat;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import com.rottenapple.util.AntiBotUtil;
import com.rottenapple.util.RavenUtil;
import java.util.List;

/**
 * Auto Block ported from Raven BS (Vanilla mode, behavior identical).
 * Dropped (dep-free agent limits, no packet/mixin/event hooks):
 * - Lag mode + lagChance/lagMaxDuration/preventDelayAttacks/blockAgainImmediately
 *   (outbound-packet hold/release is impossible without packet interception).
 * - forceBlockAnimation (touched mixin-added renderer flags; vanilla renders the
 *   blocking pose from the real use-key state on its own).
 * - MouseEvent/UseItemEvent/RightClickMouseEvent cancels; the manual-block
 *   polling path below produces the same observable blocking.
 */
public class Autoblock extends Module {
    private static final String[] UNBLOCK_OUT_OF_RANGE_MODES = new String[]{"Once", "Always"};
    private static final int UNBLOCK_ONCE = 0;
    private static final int UNBLOCK_ALWAYS = 1;

    private final SliderSetting range;
    private final SliderSetting maxHurtTimeMs;
    private final SliderSetting maxHoldMs;
    private final SliderSetting cooldownMs;
    private final SliderSetting unblockOutOfRange;

    private final ButtonSetting requireLmb;
    private final ButtonSetting requireRmb;
    private final ButtonSetting onlyWhenDamaged;
    private final ButtonSetting ignoreTeammates;

    private boolean isBlocking;
    private boolean manualBlock;
    private boolean targetWasInRange;
    private boolean unblockedAfterLeavingRange;
    private int blockStartTick = -1;
    private long lastBlockEndTimeMs;
    private Object currentTarget;
    private int lastSelfHurtTime;

    private int tickCounter;

    public Autoblock() {
        super("Auto Block", Category.combat);
        this.registerSetting(range = new SliderSetting("Range", 4.0, 2.0, 6.0, 0.1));
        this.registerSetting(maxHurtTimeMs = new SliderSetting("Maximum hurt time", "ms", 200, 50, 500, 50));
        this.registerSetting(maxHoldMs = new SliderSetting("Maximum hold duration", "ms", 150, 50, 500, 50));
        this.registerSetting(cooldownMs = new SliderSetting("Cooldown", "ms", 0, 0, 500, 50));

        this.registerSetting(unblockOutOfRange = new SliderSetting("Unblock out of range", true, 0, UNBLOCK_OUT_OF_RANGE_MODES));

        this.registerSetting(requireLmb = new ButtonSetting("Require left mouse", true));
        this.registerSetting(requireRmb = new ButtonSetting("Require right mouse", false));
        this.registerSetting(onlyWhenDamaged = new ButtonSetting("Damaged", false));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0) return 0;
        return (int) Math.ceil(ms / 50.0);
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        resetState(false);
    }

    @Override
    public void onDisable() {
        resetState(true);
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck()) {
            resetState(true);
            return;
        }
        Object p = MC.player();
        if (MC.isDead(p) || MC.screen() != null) {
            resetState(true);
            return;
        }

        int selfHurtTime = MC.hurtTime(p);
        boolean hurtAgain = selfHurtTime > lastSelfHurtTime;
        lastSelfHurtTime = selfHurtTime;

        if (!MC.holdingSword()) {
            resetState(false);
            return;
        }

        tickCounter++;
        int currentTick = tickCounter;

        currentTarget = findTarget(range.getInput() * range.getInput(), ignoreTeammates.isToggled());
        boolean rmbDown = MC.mouseDown(1);
        boolean lmbDown = MC.mouseDown(0);
        boolean hasTarget = currentTarget != null;
        boolean conditionsMet = hasTarget && checkConditions(lmbDown, rmbDown);
        boolean leftTargetRange = targetWasInRange && !hasTarget;
        targetWasInRange = hasTarget;
        int unblockMode = (int) unblockOutOfRange.getInput();

        if (unblockMode != UNBLOCK_ONCE || !rmbDown || hasTarget) {
            unblockedAfterLeavingRange = false;
        }

        if (unblockMode == UNBLOCK_ALWAYS && !hasTarget) {
            stopBlocking(true);
            manualBlock = false;
            return;
        }

        if (unblockMode == UNBLOCK_ONCE && rmbDown && leftTargetRange) {
            stopBlocking(true);
            manualBlock = false;
            unblockedAfterLeavingRange = true;
            return;
        }

        if (hurtAgain) {
            stopBlocking(true);
            manualBlock = false;
        }

        if (!conditionsMet && rmbDown) {
            if (unblockedAfterLeavingRange) {
                return;
            }
            if (!isBlocking) {
                startBlocking(currentTick);
            }
            manualBlock = true;
            return;
        }

        if (manualBlock) {
            stopBlocking(true);
            manualBlock = false;
        }

        if (!conditionsMet) {
            stopBlocking(true);
            return;
        }

        if (!isBlocking) {
            if (shouldPredictiveBlock()) {
                startBlocking(currentTick);
            }
        }

        if (isBlocking) {
            int maxHoldTicks = RavenUtil.msToTicks(maxHoldMs.getInput());
            boolean timeExpired = maxHoldTicks > 0 && blockStartTick >= 0 && currentTick - blockStartTick >= maxHoldTicks;
            if (timeExpired) {
                stopBlocking(true);
            }
        }
    }

    private boolean checkConditions(boolean lmbDown, boolean rmbDown) {
        if (requireLmb.isToggled() && !lmbDown) return false;
        if (requireRmb.isToggled() && !rmbDown) return false;
        return true;
    }

    private boolean shouldPredictiveBlock() {
        Object p = MC.player();
        int ourHurtTime = p == null ? 0 : MC.hurtTime(p);
        int triggerTick = (int) Math.round(maxHurtTimeMs.getInput() / 50.0);
        triggerTick = Math.max(1, Math.min(10, triggerTick));
        return ourHurtTime == triggerTick || (!onlyWhenDamaged.isToggled() && ourHurtTime == 0);
    }

    private void startBlocking(int currentTick) {
        if (!MC.holdingSword() || isCooldownActive()) return;
        int keyCode = MC.keyCode(MC.keyBind("keyBindUseItem"));
        MC.setKeyState(keyCode, true);
        MC.onTickKey(keyCode);
        isBlocking = true;
        blockStartTick = currentTick;
    }

    private void stopBlocking(boolean forceRelease) {
        if (!isBlocking && !forceRelease) return;
        boolean wasBlocking = isBlocking;
        int keyCode = MC.keyCode(MC.keyBind("keyBindUseItem"));
        MC.setKeyState(keyCode, false);
        isBlocking = false;
        blockStartTick = -1;
        if (wasBlocking) {
            lastBlockEndTimeMs = System.currentTimeMillis();
        }
    }

    private boolean isCooldownActive() {
        double cooldown = cooldownMs.getInput();
        return cooldown > 0 && lastBlockEndTimeMs > 0
                && System.currentTimeMillis() - lastBlockEndTimeMs < cooldown;
    }

    public boolean isActive() {
        return isEnabled() && isBlocking;
    }

    private void resetState(boolean releaseUseKey) {
        boolean restorePhysicalUse = false;
        try {
            Object useKb = MC.keyBind("keyBindUseItem");
            restorePhysicalUse = isBlocking
                    && MC.kbDown(useKb)
                    && MC.mouseDown(1)
                    && MC.screen() == null;
        } catch (Throwable ignored) {
        }
        stopBlocking(releaseUseKey);
        manualBlock = false;
        targetWasInRange = false;
        unblockedAfterLeavingRange = false;
        lastBlockEndTimeMs = 0L;
        currentTarget = null;
        lastSelfHurtTime = 0;
        if (restorePhysicalUse) {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindUseItem")), true);
        }
    }

    // ---------- targeting (mirrors Raven's CombatTargeting) ----------

    private static Object findTarget(double maxDistanceSq, boolean ignoreTeammates) {
        Object mouseOver = mouseOverTarget(maxDistanceSq, ignoreTeammates);
        if (mouseOver != null) return mouseOver;
        return closestTarget(maxDistanceSq, ignoreTeammates);
    }

    private static Object mouseOverTarget(double maxDistanceSq, boolean ignoreTeammates) {
        Object mop = MC.get(MC.mc(), "objectMouseOver");
        if (mop == null) return null;
        return asValidPlayer(MC.mopEntity(mop), maxDistanceSq, ignoreTeammates);
    }

    private static Object asValidPlayer(Object entity, double maxDistanceSq, boolean ignoreTeammates) {
        if (entity == null || !MC.isPlayer(entity)) return null;
        return isValidPlayer(entity, maxDistanceSq, ignoreTeammates) ? entity : null;
    }

    private static boolean isValidPlayer(Object player, double maxDistanceSq, boolean ignoreTeammates) {
        return isTrackablePlayer(player, ignoreTeammates) && distSqEyeAABB(player) <= maxDistanceSq;
    }

    private static boolean isTrackablePlayer(Object player, boolean ignoreTeammates) {
        if (!MC.nullCheck() || player == null) return false;
        Object self = MC.player();
        if (player == self || MC.isDead(player) || MC.deathTime(player) != 0) return false;
        if (AntiBotUtil.isBot(player)) return false;
        if (ignoreTeammates && MC.isTeammate(player)) return false;
        return true;
    }

    private static Object closestTarget(double maxDistanceSq, boolean ignoreTeammates) {
        Object world = MC.world();
        if (world == null) return null;
        Object closest = null;
        double best = Double.MAX_VALUE;
        List<Object> players = MC.players(world);
        for (Object player : players) {
            if (!isTrackablePlayer(player, ignoreTeammates)) continue;
            double d = distSqEyeAABB(player);
            if (d < best) {
                best = d;
                closest = player;
            }
        }
        return (closest != null && best <= maxDistanceSq) ? closest : null;
    }

    private static double distSqEyeAABB(Object entity) {
        try {
            Object self = MC.player();
            if (self == null || entity == null) return Double.MAX_VALUE;
            Object eyes = MC.positionEyes(self, 1.0f);
            double[] e = MC.vecXYZ(eyes);
            double[] b = MC.boxCoords(MC.entityBox(entity));
            if (e == null || b == null) return Double.MAX_VALUE;
            float border = MC.collisionBorder(entity);
            double cx = Math.max(b[0] - border, Math.min(e[0], b[3] + border));
            double cy = Math.max(b[1] - border, Math.min(e[1], b[4] + border));
            double cz = Math.max(b[2] - border, Math.min(e[2], b[5] + border));
            double dx = e[0] - cx, dy = e[1] - cy, dz = e[2] - cz;
            return dx * dx + dy * dy + dz * dz;
        } catch (Throwable ignored) {
            return Double.MAX_VALUE;
        }
    }
}
