package keystrokesmod.module.impl.movement;

import com.google.gson.JsonObject;
import keystrokesmod.clickgui.ClickGui;
import keystrokesmod.event.JumpEvent;
import keystrokesmod.event.PreMovementPacketEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.client.Settings;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.concurrent.ConcurrentLinkedQueue;

public class InvMove extends Module {
    private static final int INVENTORY_MODE_LEGIT = 1;
    private static final int INVENTORY_MODE_BLINK = 2;
    private static final int INVENTORY_MODE_CLOSE = 3;
    private static final double LEGACY_SLOW_DELAY_MS = 250.0D;
    private static final double TICK_DURATION_MS = 50.0D;

    public SliderSetting inventory;
    private SliderSetting interactDelay;
    private SliderSetting chestAndOthers;
    private SliderSetting motion;
    private ButtonSetting modifyMotionPost;
    private ButtonSetting slowWhenNecessary;
    private ButtonSetting allowJumping;
    private ButtonSetting allowSneak;
    private ButtonSetting allowMidair;
    private ButtonSetting allowSprinting;
    private ButtonSetting allowRotating;

    public int ticks;
    public boolean setMotion;
    private int movementReleaseTicks;
    private int legitCloseReleaseTicks;
    private boolean legitInventorySession;
    private boolean restoreMovementAfterLegitClose;
    private boolean restoreRestrictedInput;
    private boolean inventoryInputActive;
    private boolean inventorySneak;
    private Packet legitClosePacket;

    private final ConcurrentLinkedQueue<Packet> blinkedPackets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Packet> legitPackets = new ConcurrentLinkedQueue<>();

    private final String[] INVENTORY_MODES = new String[] { "Vanilla", "Legit", "Blink", "Close" };
    private final String[] CHEST_AND_OTHER_MODES = new String[] { "Vanilla", "Blink" };

    public InvMove() {
        super("InvMove", category.movement);
        this.registerSetting(inventory = new SliderSetting("Inventory", true, 0, INVENTORY_MODES) {
            @Override
            public void loadProfile(JsonObject data) {
                if (data.has(getProfileKey()) && data.get(getProfileKey()).isJsonPrimitive()) {
                    if (data.getAsJsonPrimitive(getProfileKey()).isString()
                        && "Legit (Slow)".equalsIgnoreCase(data.getAsJsonPrimitive(getProfileKey()).getAsString())) {
                        setValue(INVENTORY_MODE_LEGIT);
                        interactDelay.setValue(LEGACY_SLOW_DELAY_MS);
                        return;
                    }
                    if (data.getAsJsonPrimitive(getProfileKey()).isNumber()) {
                        int legacyMode = data.getAsJsonPrimitive(getProfileKey()).getAsInt();
                        if (legacyMode == 2) {
                            setValue(INVENTORY_MODE_LEGIT);
                            interactDelay.setValue(LEGACY_SLOW_DELAY_MS);
                            return;
                        }
                        if (legacyMode >= 3) {
                            setValue(legacyMode - 1);
                            return;
                        }
                    }
                }
                super.loadProfile(data);
            }
        });
        this.registerSetting(interactDelay = new SliderSetting("Interact delay", "ms", 50.0D, 50.0D, 1000.0D, 50.0D));
        this.registerSetting(chestAndOthers = new SliderSetting("Chest & others", true, 0, CHEST_AND_OTHER_MODES));
        this.registerSetting(motion = new SliderSetting("Motion", "x", 1, 0.05, 1, 0.01));
        this.registerSetting(modifyMotionPost = new ButtonSetting("Modify motion after click", false));
        this.registerSetting(slowWhenNecessary = new ButtonSetting("Slow motion when necessary", false));
        this.registerSetting(allowJumping = new ButtonSetting("Allow jumping", true));
        this.registerSetting(allowSneak = new ButtonSetting("Allow sneak", true));
        this.registerSetting(allowMidair = new ButtonSetting("Allow midair", true));
        this.registerSetting(allowRotating = new ButtonSetting("Allow rotating", true));
        this.registerSetting(allowSprinting = new ButtonSetting("Allow sprinting", true));
    }

    @Override
    public void guiUpdate() {
        interactDelay.setVisible(inventory.getInput() == INVENTORY_MODE_LEGIT, this);
    }

    @Override
    public void onDisable() {
        reset();
        releasePackets();
        releaseLegitPackets();
        releaseLegitClosePacket();
        if (restoreMovementAfterLegitClose && mc.currentScreen == null) {
            restoreMovementKeys();
        }
        legitInventorySession = false;
        restoreMovementAfterLegitClose = false;
        inventoryInputActive = false;
        inventorySneak = false;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (isLegitInventoryMode() && mc.currentScreen instanceof GuiInventory) {
            legitInventorySession = true;
        }
        else if (legitClosePacket == null && legitPackets.isEmpty()) {
            legitInventorySession = false;
        }
        if (legitClosePacket != null) {
            if (legitCloseReleaseTicks > 0 || movementReleaseTicks > 0) {
                releaseMovementKeys();
            }
            else {
                releaseCloseActionKeys();
            }
        }
        if (restoreMovementAfterLegitClose && mc.currentScreen == null && movementReleaseTicks > 0) {
            ticks = 0;
            setMotion = false;
            releaseMovementKeys();
            --movementReleaseTicks;
            return;
        }
        if (restoreMovementAfterLegitClose && mc.currentScreen == null && legitClosePacket == null) {
            restoreMovementAfterLegitClose = false;
            restoreMovementKeys();
        }
        if (!guiCheck()) {
            inventoryInputActive = false;
            inventorySneak = false;
            if (restoreRestrictedInput && mc.currentScreen == null) {
                restoreMovementKeys();
                restoreRestrictedInput = false;
            }
            reset();
            return;
        }
        if (!(mc.currentScreen instanceof ClickGui)) {
            if (setMotion) {
                if (++ticks == 10) {
                    ticks = 0;
                    setMotion = false;
                }
            }

            if (setMotion && !isSneakRequested() && (motion.getInput() != 1 || (slowWhenNecessary.isToggled()))) {
                final int speedAmplifier = Utils.getSpeedAmplifier();
                double slowedMotion = 0.65;
                switch (speedAmplifier) {
                    case 1:
                        slowedMotion = 0.615;
                        break;
                    case 2:
                        slowedMotion = 0.3;
                        break;
                }
                Utils.setSpeed(Utils.getHorizontalSpeed() * (slowWhenNecessary.isToggled() ? slowedMotion : motion.getInput()));
            }
        }
        else {
            reset();
        }

        boolean releaseMovement = shouldReleaseMovement();
        if (releaseMovement) {
            releaseMovementKeys();
            if (movementReleaseTicks > 0) {
                --movementReleaseTicks;
            }
        }
        else {
            restoreMovementKeys();
        }
        restoreRestrictedInput = !allowSneak.isToggled() || !allowMidair.isToggled() && !mc.thePlayer.onGround;
        boolean sneakRequested = isSneakRequested();
        boolean foodLvlMet = (float)mc.thePlayer.getFoodStats().getFoodLevel() > 6.0F || mc.thePlayer.capabilities.allowFlying; // from mc
        if (!releaseMovement && !sneakRequested && ((Utils.isBindDown(mc.gameSettings.keyBindSprint) || ModuleManager.sprint.isEnabled()) && mc.thePlayer.movementInput.moveForward >= 0.8F && foodLvlMet && !mc.thePlayer.isSprinting()) && allowSprinting.isToggled()) {
            mc.thePlayer.setSprinting(true);
        }
        if (releaseMovement || sneakRequested || !allowSprinting.isToggled()) {
            mc.thePlayer.setSprinting(false);
        }
        if (allowRotating.isToggled()) {
            if (Keyboard.isKeyDown(208) && mc.thePlayer.rotationPitch < 90.0F) {
                mc.thePlayer.rotationPitch += 6.0F;
            }
            if (Keyboard.isKeyDown(200) && mc.thePlayer.rotationPitch > -90.0F) {
                mc.thePlayer.rotationPitch -= 6.0F;
            }
            if (Keyboard.isKeyDown(205)) {
                mc.thePlayer.rotationYaw += 6.0F;
            }
            if (Keyboard.isKeyDown(203)) {
                mc.thePlayer.rotationYaw -= 6.0F;
            }
        }
    }

    @SubscribeEvent
    public void onJump(JumpEvent e) {
        if (e.entity == mc.thePlayer && !allowJumping.isToggled() && mc.currentScreen != null && !(mc.currentScreen instanceof ClickGui)) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!guiCheck()) {
            inventoryInputActive = false;
            inventorySneak = false;
            return;
        }

        boolean sneak = allowSneak.isToggled() && e.isSneak();
        if (!allowMidair.isToggled() && !mc.thePlayer.onGround) {
            e.setForward(0.0F);
            e.setStrafe(0.0F);
            e.setJump(false);
            sneak = false;
        }

        e.setSneak(sneak);
        if (sneak) {
            e.setSneakSlowDownMultiplier(0.3D);
            mc.thePlayer.setSprinting(false);
        }
        if (inventoryInputActive && sneak != inventorySneak) {
            e.setForward(0.0F);
            e.setStrafe(0.0F);
            e.setJump(false);
        }
        inventoryInputActive = true;
        inventorySneak = sneak;
    }

    @SubscribeEvent
    public void onPreMovementPacket(PreMovementPacketEvent e) {
        releaseLegitPackets();
        boolean releasedClose = false;
        if (legitClosePacket != null) {
            if (legitCloseReleaseTicks > 0) {
                --legitCloseReleaseTicks;
            }
            else {
                releasedClose = releaseLegitClosePacket();
            }
        }
        if (releasedClose && restoreMovementAfterLegitClose && movementReleaseTicks <= 0) {
            restoreMovementAfterLegitClose = false;
            if (mc.currentScreen == null) {
                restoreMovementKeys();
            }
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C0EPacketClickWindow) {
            boolean inventoryManagerClick = ModuleManager.inventory != null && ModuleManager.inventory.isSendingInventoryClick();
            if (isLegitInventoryMode() && mc.currentScreen instanceof GuiInventory) {
                legitInventorySession = true;
                movementReleaseTicks = Math.max(movementReleaseTicks, getMovementReleaseTicks());
                releaseMovementKeys();
                legitPackets.add(e.getPacket());
                e.setCanceled(true);
            }
            if (modifyMotionPost.isToggled() || (slowWhenNecessary.isToggled() && !canBlink())) {
                setMotion = true;
                ticks = 0;
            }
            if (!inventoryManagerClick && canBlink()) {
                blinkedPackets.add(e.getPacket());
                e.setCanceled(true);
            }
        }
        else if (e.getPacket() instanceof C0DPacketCloseWindow) {
            boolean pendingLegitClick = !legitPackets.isEmpty();
            boolean legitInventoryClose = isLegitInventoryMode() && (mc.currentScreen instanceof GuiInventory || legitInventorySession);
            if (legitInventoryClose || pendingLegitClick || legitClosePacket != null) {
                boolean activeMovementRelease = movementReleaseTicks > 0;
                if (pendingLegitClick || activeMovementRelease) {
                    if (pendingLegitClick) {
                        movementReleaseTicks = Math.max(movementReleaseTicks, getMovementReleaseTicks());
                    }
                    releaseMovementKeys();
                }
                else {
                    releaseCloseActionKeys();
                }
                restoreMovementAfterLegitClose = true;
                if (legitClosePacket == null) {
                    legitClosePacket = e.getPacket();
                    legitCloseReleaseTicks = pendingLegitClick ? 1 : 0;
                }
                e.setCanceled(true);
            }
            else {
                legitInventorySession = false;
                if (canBlink()) {
                    if (inventory.getInput() == INVENTORY_MODE_CLOSE) {
                        PacketUtils.sendPacketNoEvent(new C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT));
                    }
                    releasePackets();
                }
            }
        }
    }

    private void reset() {
        ticks = 0;
        setMotion = false;
        movementReleaseTicks = 0;
    }

    private boolean guiCheck() {
        if (mc.currentScreen == null) {
            return false;
        }
        if (Settings.inInventory()) {
            if (inventory.getInput() == -1) {
                return false;
            }
        }
        else if ((chestAndOthers.getInput() == -1 && !(mc.currentScreen instanceof ClickGui)) || (mc.currentScreen instanceof GuiChat)) {
            return false;
        }
        return true;
    }

    private boolean canBlink() {
        if (mc.currentScreen == null && inventory.getInput() != INVENTORY_MODE_CLOSE) {
            return false;
        }
        else if ((mc.currentScreen instanceof GuiInventory && inventory.getInput() == INVENTORY_MODE_BLINK) || (inventory.getInput() == INVENTORY_MODE_CLOSE && mc.currentScreen == null)) {
            return true;
        }
        else if (chestAndOthers.getInput() == 1 && !(mc.currentScreen instanceof ClickGui) && !(mc.currentScreen instanceof GuiChat)) {
            return true;
        }
        return false;
    }

    private boolean isLegitInventoryMode() {
        return inventory.getInput() == INVENTORY_MODE_LEGIT;
    }

    private int getMovementReleaseTicks() {
        return (int)(interactDelay.getInput() / TICK_DURATION_MS);
    }

    public boolean isCloseInventoryMode() {
        return inventory.getInput() == INVENTORY_MODE_CLOSE;
    }

    private boolean shouldReleaseMovement() {
        return (!allowMidair.isToggled() && !mc.thePlayer.onGround)
            || (mc.currentScreen instanceof GuiInventory
                && isLegitInventoryMode()
                && (movementReleaseTicks > 0 || mc.thePlayer.inventory.getItemStack() != null));
    }

    private boolean isSneakRequested() {
        return allowSneak.isToggled()
            && (Utils.isBindDown(mc.gameSettings.keyBindSneak) || mc.thePlayer.movementInput.sneak);
    }

    private void restoreMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindForward));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindBack));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindRight));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindLeft));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), Utils.jumpDown());
        boolean sneak = (mc.currentScreen == null || allowSneak.isToggled()) && Utils.isBindDown(mc.gameSettings.keyBindSneak);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), sneak);
        boolean sprint = (mc.currentScreen == null || allowSprinting.isToggled() && !sneak) && Utils.isBindDown(mc.gameSettings.keyBindSprint);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), sprint);
    }

    private void releaseMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.moveForward = 0.0F;
            mc.thePlayer.movementInput.moveStrafe = 0.0F;
            mc.thePlayer.movementInput.jump = false;
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.setSprinting(false);
        }
    }

    private void releaseCloseActionKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindForward));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindBack));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindRight));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), Utils.isBindDown(mc.gameSettings.keyBindLeft));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), Utils.jumpDown());
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        if (mc.thePlayer != null && mc.thePlayer.movementInput != null) {
            mc.thePlayer.movementInput.sneak = false;
            mc.thePlayer.setSprinting(false);
        }
    }

    private void releasePackets() {
        synchronized (blinkedPackets) {
            for (Packet packet : blinkedPackets) {
                PacketUtils.sendPacketNoEvent(packet);
            }
        }
        blinkedPackets.clear();
    }

    private void releaseLegitPackets() {
        Packet packet;
        while ((packet = legitPackets.poll()) != null) {
            PacketUtils.sendPacketNoEvent(packet);
        }
    }

    private boolean releaseLegitClosePacket() {
        Packet packet = legitClosePacket;
        if (packet == null) {
            return false;
        }
        legitClosePacket = null;
        legitCloseReleaseTicks = 0;
        legitInventorySession = false;
        PacketUtils.sendPacketNoEvent(packet);
        return true;
    }
}
