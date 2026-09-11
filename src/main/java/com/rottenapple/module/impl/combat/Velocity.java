package com.rottenapple.module.impl.combat;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.ModuleManager;
import com.rottenapple.module.SliderSetting;

/**
 * Velocity ported from Raven BS (behavior identical).
 * Raven reacts to LivingUpdateEvent with hurtTime == maxHurtTime; the poll loop
 * additionally accepts a rising hurtTime edge so a 50ms poll never skips the hit.
 */
public class Velocity extends Module {
    public static SliderSetting horizontal;
    public static SliderSetting vertical;
    private SliderSetting chance;
    private ButtonSetting onlyWhileTargeting;
    private ButtonSetting disableS;
    public boolean disable;

    private int lastHurtTime;

    public Velocity() {
        super("Velocity", Category.combat, 0);
        this.registerSetting(horizontal = new SliderSetting("Horizontal", "%", 90.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(vertical = new SliderSetting("Vertical", "%", 100.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(chance = new SliderSetting("Chance", "%", 100.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(onlyWhileTargeting = new ButtonSetting("Only while targeting", false));
        this.registerSetting(disableS = new ButtonSetting("Disable while holding S", false));
    }

    @Override
    public String getInfo() {
        return (int) horizontal.getInput() + "%" + " " + (int) vertical.getInput() + "%";
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck() || disable) {
            return;
        }
        // AntiKnockback sibling not ported: treated as disabled (Raven skips when it is on).
        if (ModuleManager.antiKnockback != null && ModuleManager.antiKnockback.isEnabled()) {
            return;
        }
        Object p = MC.player();
        int hurt = MC.hurtTime(p);
        int max = MC.maxHurtTime(p);
        boolean freshHit = hurt == max || hurt > lastHurtTime;
        lastHurtTime = hurt;
        if (max <= 0 || !freshHit) {
            return;
        }
        if (onlyWhileTargeting.isToggled()) {
            Object mop = MC.get(MC.mc(), "objectMouseOver");
            if (mop == null || MC.mopEntity(mop) == null) {
                return;
            }
        }
        if (disableS.isToggled() && MC.keyDown(MC.keyCode(MC.keyBind("keyBindBack")))) {
            return;
        }
        if (chance.getInput() == 0) {
            return;
        }
        if (chance.getInput() != 100) {
            double ch = Math.random();
            if (ch >= chance.getInput() / 100.0D) {
                return;
            }
        }
        double mx = MC.motionX(p);
        double my = MC.motionY(p);
        double mz = MC.motionZ(p);
        if (horizontal.getInput() != 100.0D) {
            mx *= horizontal.getInput() / 100;
            mz *= horizontal.getInput() / 100;
        }
        if (vertical.getInput() != 100.0D) {
            my *= vertical.getInput() / 100;
        }
        MC.setMotion(p, mx, my, mz);
    }
}
