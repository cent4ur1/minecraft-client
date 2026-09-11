package com.rottenapple.module.impl.player;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.DescriptionSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import com.rottenapple.util.RavenUtil;
import java.util.List;

/**
 * Bridge Assist ported from Raven BS.
 * Port notes (dep-free agent limits):
 * - Raven mutated the movement-input event pre-tick; here sneak is driven via
 *   the real sneak key state, which the game consumes identically.
 * - The full SimulatedPlayer prediction is replaced by offsetting the live
 *   bounding box by current motion (same edge-offset math afterwards).
 * - prePlace aim assist needs rotation spoofing (dropped with its helpers).
 * - C08 placement detection is approximated via held-stack-size decrease.
 * - Hotbar-scroll cancel is approximated by reverting external slot changes.
 */
public class BridgeAssist extends Module {
    private final SliderSetting edgeOffset;
    private final SliderSetting unsneakDelay;
    private final SliderSetting sneakOnJump;
    private final ButtonSetting sneakKeyPressed;
    private final ButtonSetting holdingBlocks;
    private final ButtonSetting lookingDown;
    private final ButtonSetting notMovingForward;

    private final ButtonSetting disableHotbarScrolling;

    private boolean sneakingFromModule;
    private boolean placed;
    private boolean forceRelease;
    private int sneakJumpDelayTicks = -1;
    private int sneakJumpStartTick = -1;
    private int unsneakDelayTicks = -1;
    private int unsneakStartTick = -1;
    private int lastHeldSize = -1;
    private int lastSeenSlot = -1;
    private int safeStreak;
    private double lastOffset = Double.NaN;

    public BridgeAssist() {
        super("Bridge Assist", Category.player);
        this.registerSetting(edgeOffset = new SliderSetting("Edge offset", " block", 0, 0, 0.3, 0.01));
        this.registerSetting(unsneakDelay = new SliderSetting("Unsneak delay", "ms", 50, 50, 300, 5));
        this.registerSetting(sneakOnJump = new SliderSetting("Sneak on jump", "ms", 0, 0, 500, 5));
        this.registerSetting(disableHotbarScrolling = new ButtonSetting("Disable hotbar scrolling", false));
        this.registerSetting(new DescriptionSetting("Conditions"));
        this.registerSetting(sneakKeyPressed = new ButtonSetting("Sneak key pressed", false));
        this.registerSetting(holdingBlocks = new ButtonSetting("Holding blocks", false));
        this.registerSetting(lookingDown = new ButtonSetting("Looking down", false));
        this.registerSetting(notMovingForward = new ButtonSetting("Not moving forward", false));
    }

    @Override
    public String getInfo() {
        double offset = edgeOffset.getInput();
        return offset == Math.rint(offset) ? Integer.toString((int) offset) : Double.toString(RavenUtil.round(offset, 2));
    }

    @Override
    public void onDisable() {
        sneakingFromModule = false;
        resetUnsneak();
        lastSeenSlot = -1;
        lastHeldSize = -1;
        // Safety: release a physically held sneak key.
        try {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindSneak")), false);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck()) return;
        Object p = MC.player();
        if (MC.screen() != null || MC.cap(p, "isFlying")) return;

        updatePlacedFlag(p);

        Object inv = MC.inventory(p);
        if (inv != null) {
            int cur = MC.currentItem(inv);
            if (disableHotbarScrolling.isToggled() && isAssistActive() && lastSeenSlot != -1 && cur != lastSeenSlot) {
                MC.setCurrentItem(inv, lastSeenSlot);
            } else {
                lastSeenSlot = cur;
            }
        }

        Object mi = MC.movementInput(p);
        int fwd = sign(mi == null ? 0f : MC.f(mi, "moveForward"));
        int strafe = sign(mi == null ? 0f : MC.f(mi, "moveStrafe"));
        boolean jump = mi != null && MC.b(mi, "jump");

        boolean manualSneak = isManualSneak();
        boolean requireSneak = sneakKeyPressed.isToggled();

        if (manualSneak && !requireSneak) {
            resetUnsneak();
            return;
        }

        if (requireSneak && (!manualSneak || (fwd == 0 && strafe == 0))) {
            if (!manualSneak) resetUnsneak();
            repressSneak();
            return;
        }

        if (notMovingForward.isToggled() && fwd > 0) {
            clearSneak();
            return;
        }
        if (lookingDown.isToggled() && MC.pitch(p) < 70) {
            clearSneak();
            return;
        }
        if (holdingBlocks.isToggled()) {
            Object held = MC.getHeldItem(p);
            if (held == null || !MC.isItemInstance(MC.stackItem(held), "ItemBlock")) {
                clearSneak();
                return;
            }
        }

        if (jump && MC.onGround(p) && (fwd != 0 || strafe != 0) && sneakOnJump.getInput() > 0) {
            if (!requireSneak || forceRelease) {
                sneakJumpStartTick = MC.ticksExisted(p);
                double raw = sneakOnJump.getInput() / 50.0;
                int base = (int) raw;
                sneakJumpDelayTicks = base + (Math.random() < (raw - base) ? 1 : 0);
                pressSneak(true);
                return;
            }
        }

        Object box = MC.entityBox(p);
        Object predicted = box == null ? null
                : MC.offsetBox(box, MC.motionX(p), MC.motionY(p), MC.motionZ(p));
        double offset = computeEdgeOffset(predicted == null ? box : predicted);
        lastOffset = offset;

        if (Double.isNaN(offset)) {
            safeStreak = 0;
            if (jump && (sneakOnJump.getInput() <= 0 || (fwd == 0 && strafe == 0))) {
                if (sneakingFromModule) tryReleaseSneak(true);
            } else if (MC.onGround(p)) {
                pressSneak(true);
            } else if (sneakingFromModule) {
                tryReleaseSneak(true);
            }
            return;
        }

        if (offset > edgeOffset.getInput()) {
            safeStreak = 0;
            pressSneak(true);
        } else if (sneakingFromModule) {
            // Async-poll compensation: Raven decides pre-physics every tick, but a
            // 50ms poll can sample a safe instant mid-stride. Hold one extra poll
            // before releasing so a single safe sample can't walk us off.
            if (safeStreak < 1) {
                safeStreak++;
                pressSneak(false);
            } else {
                safeStreak = 0;
                tryReleaseSneak(true);
            }
        }
    }

    private static int sign(float v) {
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    private void updatePlacedFlag(Object p) {
        int sz = -1;
        try {
            Object held = MC.getHeldItem(p);
            if (held != null && MC.isItemInstance(MC.stackItem(held), "ItemBlock")) {
                sz = MC.stackSize(held);
            }
        } catch (Throwable ignored) {
        }
        if (sneakingFromModule && lastHeldSize != -1 && sz != -1 && sz < lastHeldSize) {
            placed = true;
        }
        lastHeldSize = sz;
    }

    private void pressSneak(boolean resetDelay) {
        if (!sneakingFromModule) {
            com.rottenapple.agent.RottenAppleAgent.status(
                    "BridgeAssist: engaging sneak (offset=" + fmtOff(lastOffset) + ")");
        }
        setSneakKey(true);
        sneakingFromModule = true;
        if (resetDelay) unsneakStartTick = -1;
        repressSneak();
    }

    private void tryReleaseSneak(boolean resetDelay) {
        Object p = MC.player();
        int existed = p == null ? 0 : MC.ticksExisted(p);
        if (unsneakStartTick == -1 && sneakJumpStartTick == -1) {
            unsneakStartTick = existed;
            double raw = (unsneakDelay.getInput() - 50) / 50.0;
            int base = (int) raw;
            unsneakDelayTicks = base + (Math.random() < (raw - base) ? 1 : 0);
        }

        if (sneakJumpStartTick != -1 && existed - sneakJumpStartTick < sneakJumpDelayTicks) {
            pressSneak(false);
            return;
        }
        if (unsneakStartTick != -1 && existed - unsneakStartTick < unsneakDelayTicks) {
            pressSneak(false);
            return;
        }

        com.rottenapple.agent.RottenAppleAgent.status(
                "BridgeAssist: released sneak (offset=" + fmtOff(lastOffset) + ")");
        releaseSneak(resetDelay);
    }

    private void releaseSneak(boolean resetDelay) {
        if (!sneakKeyPressed.isToggled()) {
            setSneakKey(false);
        } else if (sneakingFromModule && isManualSneak() && (placed || !MC.onGround(MC.player()))) {
            MC.setKeyState(sneakCode(), false);
            setSneakKey(false);
            forceRelease = true;
        } else if (forceRelease) {
            setSneakKey(false);
        }

        sneakingFromModule = false;
        placed = false;
        if (resetDelay) resetUnsneak();
    }

    private void repressSneak() {
        if (forceRelease && isManualSneak()) {
            MC.setKeyState(sneakCode(), true);
            setSneakKey(true);
        }
        forceRelease = false;
    }

    private void clearSneak() {
        sneakingFromModule = false;
        resetUnsneak();
        if (sneakKeyPressed.isToggled()) repressSneak();
    }

    private void resetUnsneak() {
        unsneakStartTick = -1;
        safeStreak = 0;
        sneakJumpStartTick = -1;
        sneakJumpDelayTicks = -1;
        unsneakDelayTicks = -1;
    }

    private void setSneakKey(boolean down) {
        MC.setKeyState(sneakCode(), down);
    }

    private int sneakCode() {
        try {
            return MC.keyCode(MC.keyBind("keyBindSneak"));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String fmtOff(double v) {
        return Double.isNaN(v) ? "NaN" : String.valueOf(Math.round(v * 100.0) / 100.0);
    }

    private boolean isManualSneak() {
        try {
            return MC.isBindDown(MC.keyBind("keyBindSneak"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isAssistActive() {
        if (!isEnabled() || !MC.nullCheck() || MC.screen() != null) return false;
        Object p = MC.player();
        if (p == null || MC.cap(p, "isFlying")) return false;
        Object mi = MC.movementInput(p);
        float forward = mi == null ? 0f : MC.f(mi, "moveForward");
        float strafe = mi == null ? 0f : MC.f(mi, "moveStrafe");
        boolean manualSneak = isManualSneak();
        if (manualSneak && !sneakKeyPressed.isToggled()) return false;
        if (sneakKeyPressed.isToggled() && (!manualSneak || forward == 0 && strafe == 0)) return false;
        if (notMovingForward.isToggled() && forward > 0) return false;
        if (lookingDown.isToggled() && MC.pitch(p) < 70) return false;
        if (holdingBlocks.isToggled()) {
            Object held = MC.getHeldItem(p);
            if (held == null || !MC.isItemInstance(MC.stackItem(held), "ItemBlock")) return false;
        }
        return true;
    }

    private double computeEdgeOffset(Object simBox) {
        double[] b = MC.boxCoords(simBox);
        if (b == null) return Double.NaN;
        Object groundCheck = MC.newBox(b[0], b[1] - 0.01, b[2], b[3], b[1], b[5]);
        List<Object> groundBoxes = MC.colliding(MC.world(), MC.player(), groundCheck);
        if (groundBoxes.isEmpty()) return Double.NaN;

        double feetX = (b[0] + b[3]) / 2.0;
        double feetZ = (b[2] + b[5]) / 2.0;

        double minDist = Double.MAX_VALUE;
        for (Object box : groundBoxes) {
            double[] c = MC.boxCoords(box);
            if (c == null) continue;
            double closestX = Math.max(c[0], Math.min(feetX, c[3]));
            double closestZ = Math.max(c[2], Math.min(feetZ, c[5]));
            double dist = Math.max(Math.abs(feetX - closestX), Math.abs(feetZ - closestZ));
            minDist = Math.min(minDist, dist);
        }
        return minDist;
    }
}
