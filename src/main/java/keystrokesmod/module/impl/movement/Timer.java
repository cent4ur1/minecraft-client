package keystrokesmod.module.impl.movement;

import keystrokesmod.mixin.impl.accessor.IAccessorMinecraft;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;

public class Timer extends Module {
    private SliderSetting speed;
    private ButtonSetting gameplayOnly;

    private static double localUpdateAccumulator;
    private static int scheduledWorldUpdates = 1;
    private static boolean playerActionTick = true;
    private static boolean pendingAttack;
    private static boolean pendingUse;

    public Timer() {
        super("Timer", category.movement);
        this.registerSetting(speed = new SliderSetting("Speed", 1.0D, 0.0D, 2.0D, 0.05).allowOutOfRangeProfileValues());
        this.registerSetting(gameplayOnly = new ButtonSetting("Gameplay only", true, "Player only"));
    }

    public double getSpeed() {
        return speed.getInput();
    }

    public void setSpeed(double value) {
        speed.setValueRawWithEvent(value);
    }

    public void resetSpeed() {
        setSpeed(1.0D);
    }

    @Override
    public String getInfo() {
        return Utils.asWholeNum(speed.getInput());
    }

    @Override
    public void onEnable() {
        resetLocalState();
        Utils.resetTimer();
    }

    @Override
    public void onDisable() {
        resetLocalState();
        Utils.resetTimer();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }

        float configuredSpeed = (float) speed.getInput();
        if (usesGameplayTiming(this)) {
            Utils.resetTimer();
        } else {
            ((IAccessorMinecraft) mc).getTimer().timerSpeed = configuredSpeed;
        }
    }

    public static void beginClientTick() {
        Timer timer = keystrokesmod.module.ModuleManager.timer;
        if (!usesGameplayTiming(timer) || !Utils.nullCheck()) {
            resetLocalState();
            return;
        }

        double configuredSpeed = Math.max(0.0D, Math.min(2.0D, timer.speed.getInput()));
        localUpdateAccumulator += configuredSpeed;
        int updates = (int) Math.floor(localUpdateAccumulator + 1.0E-9D);
        localUpdateAccumulator -= updates;
        scheduledWorldUpdates = updates;
        playerActionTick = updates > 0;
    }

    public static int consumeExtraLocalUpdatesForBaseTick() {
        return 0;
    }

    public static boolean shouldSkipBaseLocalUpdate() {
        return false;
    }

    public static int getWorldUpdateCount() {
        Timer timer = keystrokesmod.module.ModuleManager.timer;
        return usesGameplayTiming(timer) && Utils.nullCheck() ? scheduledWorldUpdates : 1;
    }

    public static float getWorldRenderPartialTicks(float partialTicks) {
        Timer timer = keystrokesmod.module.ModuleManager.timer;
        if (!usesGameplayTiming(timer) || !Utils.nullCheck()) {
            return partialTicks;
        }
        double renderPartialTicks = localUpdateAccumulator + partialTicks * timer.speed.getInput();
        return (float) Math.max(0.0D, Math.min(1.0D, renderPartialTicks));
    }

    public static boolean allowAttack() {
        if (shouldProcessPlayerActions()) {
            return true;
        }
        pendingAttack = true;
        return false;
    }

    public static boolean allowUse() {
        if (shouldProcessPlayerActions()) {
            return true;
        }
        pendingUse = true;
        return false;
    }

    public static boolean shouldProcessPlayerActions() {
        Timer timer = keystrokesmod.module.ModuleManager.timer;
        return !usesGameplayTiming(timer) || !Utils.nullCheck() || playerActionTick;
    }

    public static void flushPendingActions() {
        if (!shouldProcessPlayerActions() || !Utils.nullCheck()) {
            return;
        }
        if (mc.currentScreen != null) {
            pendingAttack = false;
            pendingUse = false;
            return;
        }
        if (pendingAttack) {
            pendingAttack = false;
            ((IAccessorMinecraft) mc).callClickMouse();
        }
        if (pendingUse) {
            pendingUse = false;
            ((IAccessorMinecraft) mc).callRightClickMouse();
        }
    }

    private static void resetLocalState() {
        localUpdateAccumulator = 0.0D;
        scheduledWorldUpdates = 1;
        playerActionTick = true;
        pendingAttack = false;
        pendingUse = false;
    }

    private static boolean usesGameplayTiming(Timer timer) {
        return timer != null
                && timer.isEnabled()
                && (timer.gameplayOnly.isToggled() || timer.speed.getInput() <= 0.0D);
    }
}
