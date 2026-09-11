package com.rottenapple.module.impl.combat;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import com.rottenapple.util.RavenUtil;

/**
 * Jump Reset ported from Raven BS (behavior identical).
 * Raven's PreUpdate/PostMotion handlers run per player tick; onTick() runs per
 * poll with the same hurtTime edge detection, so timing matches.
 */
public class JumpReset extends Module {
    private SliderSetting chance;
    private ButtonSetting requireMouseDown;
    private ButtonSetting requireMovingForward;
    private ButtonSetting requireAim;

    private boolean setJump;
    private boolean ignoreNext;
    private int lastHurtTime;
    private double lastFallDistance;

    public JumpReset() {
        super("Jump Reset", Category.combat);
        this.registerSetting(chance = new SliderSetting("Chance", "%", 80, 0, 100, 1));
        this.registerSetting(requireMouseDown = new ButtonSetting("Require mouse down", false));
        this.registerSetting(requireMovingForward = new ButtonSetting("Require moving forward", true));
        this.registerSetting(requireAim = new ButtonSetting("Require aim", true));
    }

    @Override
    public String getInfo() {
        return (int) chance.getInput() == 100 ? "" : ((int) chance.getInput()) + "%";
    }

    @Override
    public void onDisable() {
        // Safety: release a physically held jump key (Raven mutates events, we hold state).
        try {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindJump")), false);
        } catch (Throwable ignored) {
        }
        setJump = false;
        ignoreNext = false;
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck()) {
            return;
        }
        Object p = MC.player();
        int hurtTime = MC.hurtTime(p);
        boolean onGround = MC.onGround(p);

        if (onGround && lastFallDistance > 3 && !MC.cap(p, "allowFlying")) {
            ignoreNext = true;
        }

        if (hurtTime > lastHurtTime) {
            boolean mouseDown = MC.kbDown(MC.keyBind("keyBindAttack")) || !requireMouseDown.isToggled();
            boolean aimingAt = !requireAim.isToggled() || checkAim();
            boolean forward = MC.kbDown(MC.keyBind("keyBindForward")) || !requireMovingForward.isToggled();
            boolean randomization = (int) chance.getInput() == 100
                    || RavenUtil.randomizeDouble(0, 100) < chance.getInput();
            Object mi = MC.movementInput(p);
            float fwd = MC.f(mi, "moveForward");
            float strafe = MC.f(mi, "moveStrafe");
            boolean fov = RavenUtil.inFov(
                    RavenUtil.getCustomDirection(MC.yaw(p), fwd, strafe),
                    330,
                    RavenUtil.deltaAngle(MC.motionX(p), MC.motionZ(p)));

            if (!ignoreNext && !MC.isBurning(p) && onGround && aimingAt && forward
                    && mouseDown && randomization && !hasBadEffect() && fov) {
                MC.setKeyState(MC.keyCode(MC.keyBind("keyBindJump")), true);
                setJump = true;
            }

            ignoreNext = false;
        }

        lastHurtTime = hurtTime;
        lastFallDistance = MC.f(p, "fallDistance");

        if (setJump && !MC.keyDown(MC.keyCode(MC.keyBind("keyBindJump")))) {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindJump")), false);
        }
    }

    private boolean hasBadEffect() {
        Object p = MC.player();
        return MC.activePotion(p, MC.potion("jump")) != null
                || MC.activePotion(p, MC.potion("poison")) != null
                || MC.activePotion(p, MC.potion("wither")) != null;
    }

    private boolean checkAim() {
        Object mop = MC.get(MC.mc(), "objectMouseOver");
        if (mop == null) return false;
        if (!"ENTITY".equals(MC.mopType(mop))) return false;
        return MC.isPlayer(MC.mopEntity(mop));
    }
}
