package com.rottenapple.module.impl.combat;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.DescriptionSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import com.rottenapple.util.RavenUtil;
import java.util.Random;

/**
 * Auto Clicker ported from Raven BS (behavior identical).
 * Raven's PrePlayerInteractEvent/RenderTick handlers run per tick; here onTick()
 * runs per poll (~50ms). CPS scheduling is wall-clock based, so cadence matches.
 * Displace interplay dropped (no Displace module): recovery flag stays false.
 */
public class AutoClicker extends Module {
    public SliderSetting targetCPS;
    public ButtonSetting simulateExhaust;
    public ButtonSetting notUsingItem;
    public ButtonSetting breakBlocks;
    public ButtonSetting weaponOnly;
    public ButtonSetting disableCreative;
    public ButtonSetting inventory;
    public SliderSetting inventoryStartDelay;

    private long nextClickTime;
    private long inventoryNextClickTime;
    private boolean isHoldingBlockBreak;
    private boolean displaceRecoveryClickRequested;

    private Random rand;

    private java.awt.Robot robot;
    private boolean robotTried;
    private boolean robotOk;
    private boolean robotNoted;

    public AutoClicker() {
        super("Auto Clicker", Category.combat, 0);
        this.registerSetting(new DescriptionSetting("Best with delay remover."));
        this.registerSetting(targetCPS = new SliderSetting("Target CPS", 10.0, 1.0, 20.0, 0.5));
        this.registerSetting(simulateExhaust = new ButtonSetting("Simulate exhaust", true));
        this.registerSetting(notUsingItem = new ButtonSetting("Not using item", false));
        this.registerSetting(breakBlocks = new ButtonSetting("Break blocks", false));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(disableCreative = new ButtonSetting("Disable in creative", false));
        this.registerSetting(inventory = new ButtonSetting("Inventory", false));
        this.registerSetting(inventoryStartDelay = new SliderSetting("Start delay", "ms", 100.0, 0.0, 250.0, 10.0));
    }

    @Override
    public String getInfo() {
        double cps = targetCPS.getInput();
        return cps == Math.rint(cps) ? Integer.toString((int) cps) : Double.toString(RavenUtil.round(cps, 1));
    }

    @Override
    public void onEnable() {
        this.rand = new Random();
        this.robotTried = false;
        this.robotOk = false;
        this.nextClickTime = 0L;
        this.inventoryNextClickTime = 0L;
        this.isHoldingBlockBreak = false;
        this.displaceRecoveryClickRequested = false;
    }

    @Override
    public void onDisable() {
        this.nextClickTime = 0L;
        this.inventoryNextClickTime = 0L;
        this.isHoldingBlockBreak = false;
        this.displaceRecoveryClickRequested = false;
        // Safety: our key states are physical, never leave attack held.
        try {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindAttack")), false);
        } catch (Throwable ignored) {
        }
        MC.pokeMouseButton(0, false);
    }

    @Override
    public void onTick() {
        inventoryTick();
        interactTick();
    }

    private boolean consumeDisplaceRecoveryClickRequest() {
        boolean requested = displaceRecoveryClickRequested;
        displaceRecoveryClickRequested = false;
        return requested;
    }

    private void inventoryTick() {
        if (!inventory.isToggled()) {
            return;
        }
        if (!MC.nullCheck()) {
            return;
        }
        if (!MC.isGuiContainer(MC.screen())) {
            inventoryNextClickTime = 0L;
            return;
        }
        if (!MC.mouseDown(0)) {
            inventoryNextClickTime = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (inventoryNextClickTime == 0L) {
            inventoryNextClickTime = now + (long) inventoryStartDelay.getInput();
        }

        int clicks = 0;
        while (inventoryNextClickTime <= now) {
            clicks++;
            inventoryNextClickTime += nextDelay();
        }
        if (clicks <= 0) {
            return;
        }

        Object gui = MC.screen();
        Object slot = MC.hoveredSlot(gui);
        if (slot == null || MC.slotNumber(slot) < 0) {
            return;
        }

        int windowId = MC.containerId(gui);
        int slotId = MC.slotNumber(slot);
        int mode = MC.shiftDown() ? 1 : 0;

        if (MC.controller() == null || MC.player() == null) {
            return;
        }

        for (int i = 0; i < clicks; i++) {
            MC.windowClick(windowId, slotId, 0, mode);
        }
    }

    private void interactTick() {
        boolean recoveryClickRequested = consumeDisplaceRecoveryClickRequest();
        if (!MC.nullCheck()) return;

        Object player = MC.player();
        int key = MC.keyCode(MC.keyBind("keyBindAttack"));
        if (MC.mouseDown(0)) {
            if (notUsingItem.isToggled() && MC.isUsingItem(player)) return;
            if (disableCreative.isToggled() && MC.cap(player, "isCreativeMode")) return;
            if (MC.screen() != null || !MC.inGameFocus()) return;
            if (weaponOnly.isToggled() && !MC.holdingWeapon()) return;

            // Mirrors Raven DelayRemover ("1.7 hitreg"): vanilla leftClickCounter
            // gates clicks to ~2 CPS; zeroing it while clicking restores target CPS.
            MC.set(MC.mc(), "leftClickCounter", Integer.valueOf(0));

            if (breakBlocks.isToggled()) {
                if (!MC.cap(player, "allowEdit")) {
                    if (this.isHoldingBlockBreak) {
                        MC.setKeyState(key, false);
                        MC.pokeMouseButton(0, false);
                        this.isHoldingBlockBreak = false;
                    }
                } else {
                    Object mop = MC.get(MC.mc(), "objectMouseOver");
                    Object pos = mop == null ? null : MC.mopPos(mop);
                    if (pos != null) {
                        Object block = MC.blockOf(MC.blockState(MC.world(), pos));
                        if (block != null && !block.equals(MC.blocksField("air"))
                                && !MC.isBlockInstance(block, "BlockLiquid")) {
                            if (!this.isHoldingBlockBreak) {
                                MC.setKeyState(key, true);
                                MC.pokeMouseButton(0, true);
                                this.isHoldingBlockBreak = true;
                            }
                            return;
                        }
                        if (this.isHoldingBlockBreak) {
                            MC.setKeyState(key, false);
                            MC.pokeMouseButton(0, false);
                            this.isHoldingBlockBreak = false;
                            return;
                        }
                    } else {
                        this.isHoldingBlockBreak = false;
                    }
                }
            }

            long now = System.currentTimeMillis();
            int clicks = 0;
            if (nextClickTime == 0L) {
                if (recoveryClickRequested) {
                    clicks = 1;
                }
                nextClickTime = now + nextDelay();
            } else {
                while (nextClickTime <= now) {
                    clicks++;
                    nextClickTime += nextDelay();
                }

                if (recoveryClickRequested && clicks == 0) {
                    clicks = 1;
                    nextClickTime = now + nextDelay();
                }
            }

            for (int i = 0; i < clicks; i++) {
                if (!robotClick()) {
                    MC.onTickKey(key);
                    MC.pokeMouseButton(0, true);
                }
            }
        } else {
            this.nextClickTime = 0L;
            this.isHoldingBlockBreak = false;
            MC.setKeyState(key, false);
            MC.pokeMouseButton(0, false);
        }
    }

    /**
     * Real OS-level click (press+release pair per scheduled click) so overlays
     * that count LWJGL input events (e.g. Lunar CPS) see every click.
     * Falls back to synthetic pressTime when Robot is unavailable (notably
     * macOS without an Accessibility grant) — those clicks hit but stay
     * invisible to event-counting overlays.
     */
    private boolean robotClick() {
        try {
            if (!robotTried) {
                robotTried = true;
                robot = new java.awt.Robot();
                robotOk = true;
            }
            if (!robotOk || robot == null) return false;
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            return true;
        } catch (Throwable t) {
            robotOk = false;
            if (!robotNoted) {
                robotNoted = true;
                com.rottenapple.agent.RottenAppleAgent.status(
                        "AutoClicker: Robot unavailable (" + t + "), synthetic clicks only.");
            }
            return false;
        }
    }

    private long nextDelay() {
        int target = Math.max(1, (int) targetCPS.getInput());
        int baseDelay = 1000 / target;

        int finalDelay;

        if (simulateExhaust.isToggled()) {
            int variation = rand.nextInt(baseDelay + 1) - (baseDelay / 2);
            finalDelay = baseDelay + variation;

            if (rand.nextInt(100) < 15) {
                if (rand.nextBoolean()) {
                    finalDelay = 25 + rand.nextInt(16);
                } else {
                    finalDelay = baseDelay + 50 + rand.nextInt(41);
                }
            }

            if (rand.nextInt(100) < 8) {
                int spikeMult = 50 + rand.nextInt(151);
                finalDelay = (finalDelay * spikeMult) / 100;
            }

            if (rand.nextInt(100) < 10) {
                finalDelay += 10 + rand.nextInt(26);
            }
        } else {
            finalDelay = baseDelay + (rand.nextInt(21) - 10);
        }

        return Math.max(33, Math.min(180, finalDelay));
    }
}
