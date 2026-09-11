package com.rottenapple.module.impl.player;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.BlockListSetting;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.ItemListSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;

/**
 * Fast Place ported from Raven BS (delay behavior identical).
 * Port notes: the C08 air-place packet cancel needs packet interception
 * (dropped); the in-world block-count overlay + its edit screen are dropped
 * (getTotalBlocks kept for parity).
 */
public class FastPlace extends Module {
    public SliderSetting tickDelay;
    public SliderSetting activationTime;
    public ButtonSetting blocksOnly, pitchCheck;
    public ButtonSetting ignoredHeldItemsToggle;
    public ItemListSetting ignoredHeldItems;
    public ButtonSetting blockBlacklistToggle;
    public BlockListSetting blockBlacklist;

    private long rightClickStartTime;

    public FastPlace() {
        super("Fast Place", Category.player);
        this.registerSetting(tickDelay = new SliderSetting("Delay", " tick", 1.0, 0.0, 3.0, 1.0));
        this.registerSetting(activationTime = new SliderSetting("Minimum hold time", "ms", 0.0, 0.0, 100.0, 5.0));
        this.registerSetting(blocksOnly = new ButtonSetting("Blocks only", true));
        this.registerSetting(pitchCheck = new ButtonSetting("Pitch check", false));
        this.registerSetting(ignoredHeldItemsToggle = new ButtonSetting("Held item blacklist", false, "Ignore held items", "Restrict held items", "Allow while holding"));
        this.registerSetting(ignoredHeldItems = new ItemListSetting("Held items", "Items"));
        this.registerSetting(blockBlacklistToggle = new ButtonSetting("Block blacklist", false, "Blocks.Block blacklist"));
        this.registerSetting(blockBlacklist = new BlockListSetting("Blacklisted blocks", "Block blacklist", "Blocks.Block blacklist", "Blocks.Blacklisted blocks"));
    }

    @Override
    public void onDisable() {
        rightClickStartTime = 0L;
    }

    @Override
    public void guiUpdate() {
        ignoredHeldItems.setVisible(ignoredHeldItemsToggle.isToggled(), this);
        blockBlacklist.setVisible(blockBlacklistToggle.isToggled(), this);
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck() || !MC.inGameFocus()) {
            rightClickStartTime = 0L;
            return;
        }

        if (!isRightClickActive()) {
            rightClickStartTime = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (rightClickStartTime == 0L) {
            rightClickStartTime = now;
        }

        if (!canFastPlace(now, true)) {
            return;
        }

        int delay = (int) tickDelay.getInput();
        if (delay == 0) {
            MC.setRcDelay(0);
        } else {
            if (delay == 4) {
                return;
            }
            if (MC.rcDelay() > delay) {
                MC.setRcDelay(delay);
            }
        }
    }

    private boolean isBlockedHoverBlock() {
        if (!blockBlacklistToggle.isToggled()) return false;
        Object mop = MC.get(MC.mc(), "objectMouseOver");
        if (mop == null || !"BLOCK".equals(MC.mopType(mop))) return false;
        Object hoveredPos = MC.mopPos(mop);
        if (hoveredPos == null) return false;
        Object state = MC.blockState(MC.world(), hoveredPos);
        Object hoveredBlock = MC.blockOf(state);
        String registryId = hoveredBlock == null ? null : MC.blockRegId(hoveredBlock);
        if (registryId == null) return false;
        int meta = MC.blockMeta(hoveredBlock, state);
        String storageId = meta != 0 ? registryId + ":" + meta : registryId;
        return blockBlacklist.contains(storageId) || blockBlacklist.contains(registryId);
    }

    private boolean isRightClickActive() {
        Object p = MC.player();
        if (p == null) return false;
        return MC.isBindDown(MC.keyBind("keyBindUseItem")) || MC.isUsingItem(p);
    }

    private boolean canFastPlace(long now, boolean requireActivationDelay) {
        Object p = MC.player();
        if (p == null) return false;
        if (blocksOnly.isToggled()) {
            Object item = MC.getHeldItem(p);
            if (item == null || !MC.isItemInstance(MC.stackItem(item), "ItemBlock")) {
                return false;
            }
        }
        if (pitchCheck.isToggled() && MC.pitch(p) < 70.0f) {
            return false;
        }
        if (ignoredHeldItemsToggle.isToggled() && ignoredHeldItems.matches(MC.getHeldItem(p))) {
            return false;
        }
        if (isBlockedHoverBlock()) {
            return false;
        }
        return !requireActivationDelay || now - rightClickStartTime >= (long) activationTime.getInput();
    }

    public int getTotalBlocks() {
        int totalBlocks = 0;
        Object inv = MC.inventory(MC.player());
        if (inv == null) {
            return 0;
        }
        for (int i = 0; i < 9; ++i) {
            Object stack = MC.getStack(inv, i);
            if (stack != null && MC.isItemInstance(MC.stackItem(stack), "ItemBlock")
                    && MC.canBePlacedItem(MC.stackItem(stack)) && MC.stackSize(stack) > 0) {
                totalBlocks += MC.stackSize(stack);
            }
        }
        return totalBlocks;
    }
}
