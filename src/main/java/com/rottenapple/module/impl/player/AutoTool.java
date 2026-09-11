package com.rottenapple.module.impl.player;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.BlockListSetting;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.GroupSetting;
import com.rottenapple.module.ItemListSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;

/**
 * Auto Tool ported from Raven BS (behavior identical).
 * Port notes: spoofItem needed mixin-added ItemRenderer flags (dropped);
 * scroll/slot-cancel events are approximated via hotbar-change detection.
 */
public class AutoTool extends Module {
    private final GroupSetting timingGroup;
    private final SliderSetting activationTime;
    private final SliderSetting hoverDelay;
    private final SliderSetting nextHoverDelay;

    private final ButtonSetting ignoredHeldItemsToggle;
    private final ItemListSetting ignoredHeldItems;

    private final GroupSetting conditionsGroup;
    private final ButtonSetting onlyWhileCrouching;
    private final ButtonSetting requireLeftMouse;
    private final ButtonSetting disableInCreative;

    private final GroupSetting swapGroup;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;

    private final ButtonSetting blockWhitelistToggle;
    private final BlockListSetting blockWhitelist;
    private final ButtonSetting blockBlacklistToggle;
    private final BlockListSetting blockBlacklist;

    private boolean hasSwapped;
    public int previousSlot = -1;
    private int tickCounter;
    private int leftMouseDownSinceTick = -1;
    private int hoverStartTick = -1;
    private int nextHoverStartTick = -1;
    private int nextHoverSlot = -1;
    private int lastAppliedSlot = -1;

    public AutoTool() {
        super("Auto Tool", Category.player);
        this.registerSetting(timingGroup = new GroupSetting("Timing"));
        this.registerSetting(activationTime = new SliderSetting(timingGroup, "Activation time", "ms", 0.0, 0.0, 1000.0, 25.0));
        this.registerSetting(hoverDelay = new SliderSetting(timingGroup, "Hover delay", "ms", 0.0, 0.0, 1000.0, 25.0));
        this.registerSetting(nextHoverDelay = new SliderSetting(timingGroup, "Next hover delay", "ms", 0.0, 0.0, 1000.0, 25.0));

        this.registerSetting(conditionsGroup = new GroupSetting("Conditions"));
        this.registerSetting(onlyWhileCrouching = new ButtonSetting(conditionsGroup, "Only while crouching", false));
        this.registerSetting(requireLeftMouse = new ButtonSetting(conditionsGroup, "Require Left mouse", true, "Require mouse down"));
        this.registerSetting(disableInCreative = new ButtonSetting(conditionsGroup, "Disable in creative", true));

        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));

        this.registerSetting(ignoredHeldItemsToggle = new ButtonSetting("Held item blacklist", false, "Ignore held items", "Restrict held items", "Allow while holding"));
        this.registerSetting(ignoredHeldItems = new ItemListSetting("Held items", "Items"));
        this.registerSetting(blockWhitelistToggle = new ButtonSetting("Block whitelist", false, "Restrict allowed blocks", "Blocks.Block whitelist"));
        this.registerSetting(blockWhitelist = new BlockListSetting("Whitelisted blocks", "Blocks", "Blocks.Whitelisted blocks"));
        this.registerSetting(blockBlacklistToggle = new ButtonSetting("Block blacklist", false, "Blocks.Block blacklist"));
        this.registerSetting(blockBlacklist = new BlockListSetting("Blacklisted blocks", "Block blacklist", "Blocks.Block blacklist", "Blocks.Blacklisted blocks"));
    }

    @Override
    public void guiUpdate() {
        activationTime.setVisible(requireLeftMouse.isToggled(), this);
        ignoredHeldItems.setVisible(ignoredHeldItemsToggle.isToggled(), this);
        blockWhitelist.setVisible(blockWhitelistToggle.isToggled(), this);
        blockBlacklist.setVisible(blockBlacklistToggle.isToggled(), this);
    }

    @Override
    public void onEnable() {
        resetState(true);
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
        Object inv = MC.inventory(p);

        int currentTick = ++tickCounter;
        boolean leftMouseDown = MC.mouseDown(0);
        updateLeftMouseState(leftMouseDown, currentTick);

        // Approximates Raven's scroll/slot-cancel events: adopt external slot changes.
        if (hasSwapped && inv != null) {
            int cur = MC.currentItem(inv);
            if (cur != lastAppliedSlot && cur != previousSlot && overrideSwapBack.isToggled()) {
                previousSlot = cur;
            }
        }

        if (!MC.inGameFocus() || MC.screen() != null || MC.isDead(p) || !MC.cap(p, "allowEdit")) {
            resetState(true);
            return;
        }

        if (disableInCreative.isToggled() && MC.cap(p, "isCreativeMode")) {
            resetState(true);
            return;
        }

        Object hoverPos = MC.blockHoverPos(MC.reach(), MC.yaw(p), MC.pitch(p));
        updateHoverState(hoverPos, currentTick);

        if (hoverPos == null || isUnsupportedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        if (onlyWhileCrouching.isToggled() && !isSneaking(p)) {
            resetSlot();
            return;
        }

        if (requireLeftMouse.isToggled()) {
            if (!leftMouseDown) {
                resetSlot();
                return;
            }
            if (!hasElapsed(leftMouseDownSinceTick, activationTime.getInput(), currentTick)) {
                resetSlot();
                return;
            }
        }

        if (!hasElapsed(hoverStartTick, hoverDelay.getInput(), currentTick)) {
            resetSlot();
            return;
        }

        if (isUseBlocked()) {
            resetSlot();
            return;
        }

        if (isBlockedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        if (blockWhitelistToggle.isToggled() && !isWhitelistedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        Object mop = MC.get(MC.mc(), "objectMouseOver");
        Object swapPos = null;
        if (mop != null && "BLOCK".equals(MC.mopType(mop))) {
            swapPos = MC.mopPos(mop);
        }
        if (swapPos == null || isUnsupportedBlock(swapPos)) {
            resetSlot();
            return;
        }

        int slot = findTool(MC.blockOf(MC.blockState(MC.world(), swapPos)));
        if (slot == -1) {
            resetNextHover();
            return;
        }

        if (previousSlot == -1 && slot != MC.currentItem(inv)) {
            previousSlot = MC.currentItem(inv);
        }

        if (!hasSwapped) {
            setSlot(slot);
            resetNextHover();
            return;
        }

        if (slot == MC.currentItem(inv)) {
            resetNextHover();
            return;
        }

        if (nextHoverSlot != slot) {
            nextHoverSlot = slot;
            nextHoverStartTick = currentTick;
        }
        if (hasElapsed(nextHoverStartTick, nextHoverDelay.getInput(), currentTick)) {
            setSlot(slot);
            resetNextHover();
        }
    }

    private boolean isSneaking(Object p) {
        try {
            Object r = MC.call(p, "isSneaking", new Class<?>[0]);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void updateLeftMouseState(boolean leftMouseDown, int currentTick) {
        if (leftMouseDown) {
            if (leftMouseDownSinceTick == -1) {
                leftMouseDownSinceTick = currentTick;
            }
        } else {
            leftMouseDownSinceTick = -1;
        }
    }

    private void updateHoverState(Object hoverPos, int currentTick) {
        if (hoverPos == null) {
            hoverStartTick = -1;
            return;
        }
        if (hoverStartTick == -1) {
            hoverStartTick = currentTick;
        }
    }

    private boolean isUseBlocked() {
        Object p = MC.player();
        boolean useActive = MC.isBindDown(MC.keyBind("keyBindUseItem")) || MC.isUsingItem(p);
        Object inv = MC.inventory(p);
        int heldItemSlot = hasSwapped && previousSlot != -1
                ? previousSlot
                : MC.currentItem(inv);
        if (ignoredHeldItemsToggle.isToggled()
                && ignoredHeldItems.matches(MC.getStack(inv, heldItemSlot))) {
            return true;
        }
        return useActive;
    }

    private boolean isBlockedBlock(Object blockPos) {
        if (!blockBlacklistToggle.isToggled()) {
            return false;
        }
        return matchesBlockList(blockPos, blockBlacklist);
    }

    private boolean isUnsupportedBlock(Object blockPos) {
        Object block = MC.blockOf(MC.blockState(MC.world(), blockPos));
        return block != null && (block.equals(MC.blocksField("bedrock")) || block.equals(MC.blocksField("barrier")));
    }

    private boolean isWhitelistedBlock(Object blockPos) {
        if (blockWhitelist.getBlocks().isEmpty()) {
            return false;
        }
        return matchesBlockList(blockPos, blockWhitelist);
    }

    private boolean matchesBlockList(Object blockPos, BlockListSetting blockList) {
        Object state = MC.blockState(MC.world(), blockPos);
        Object hoveredBlock = MC.blockOf(state);
        String registryId = hoveredBlock == null ? null : MC.blockRegId(hoveredBlock);
        if (registryId == null) {
            return false;
        }
        int meta = MC.blockMeta(hoveredBlock, state);
        String storageId = meta != 0 ? registryId + ":" + meta : registryId;
        return blockList.contains(storageId) || blockList.contains(registryId);
    }

    private int findTool(Object block) {
        double bestScore = 1.0D;
        int bestSlot = -1;
        Object inv = MC.inventory(MC.player());
        if (inv == null) return -1;
        for (int i = 0; i < 9; ++i) {
            Object stack = MC.getStack(inv, i);
            if (stack != null) {
                double score = MC.toolScore(stack, block);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private boolean hasElapsed(int startTick, double requiredMs, int currentTick) {
        int requiredTicks = getRequiredTicks(requiredMs);
        if (requiredTicks <= 0) {
            return true;
        }
        return startTick != -1 && currentTick - startTick >= requiredTicks;
    }

    private int getRequiredTicks(double requiredMs) {
        if (requiredMs <= 0.0) {
            return 0;
        }
        return (int) Math.ceil(requiredMs / 50.0);
    }

    private void resetState(boolean resetTimers) {
        if (resetTimers) {
            tickCounter = 0;
            leftMouseDownSinceTick = -1;
            hoverStartTick = -1;
        }
        resetSlot();
    }

    private void resetSlot() {
        if (previousSlot != -1 && switchBackWhenDone.isToggled()) {
            setSlot(previousSlot);
        }
        previousSlot = -1;
        hasSwapped = false;
        resetNextHover();
    }

    private void resetNextHover() {
        nextHoverStartTick = -1;
        nextHoverSlot = -1;
    }

    private void setSlot(int slot) {
        Object inv = MC.inventory(MC.player());
        if (inv == null || slot == -1 || slot == MC.currentItem(inv)) {
            return;
        }
        MC.setCurrentItem(inv, slot);
        lastAppliedSlot = slot;
        hasSwapped = true;
        MC.syncItem();
    }
}
