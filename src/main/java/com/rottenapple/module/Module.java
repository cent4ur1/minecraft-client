package com.rottenapple.module;

import com.rottenapple.bridge.MC;
import java.util.ArrayList;

/**
 * Toggleable cheat module (mirrors Raven's Module, minus Forge event bus).
 * Enabled modules get onTick() every poll (~50ms, same cadence as a client tick).
 */
public class Module {
    protected ArrayList<Setting> settings;
    private String moduleName;
    private Module.Category moduleCategory;
    private volatile boolean enabled;
    private int keycode;
    private boolean keyDown;

    public Module(String moduleName, Module.Category moduleCategory, int keycode) {
        this.moduleName = moduleName;
        this.moduleCategory = moduleCategory;
        this.keycode = keycode;
        this.enabled = false;
        this.settings = new ArrayList<Setting>();
    }

    public Module(String name, Module.Category moduleCategory) {
        this(name, moduleCategory, 0);
    }

    /** Edge-triggered keybind poll (mirrors Raven's onKeyBind; supports mouse binds >= 1000). */
    public void onKeyBind() {
        if (this.keycode == 0) {
            return;
        }
        try {
            boolean down = this.keycode >= 1000
                    ? MC.mouseDown(this.keycode - 1000)
                    : MC.keyDown(this.keycode);
            if (!this.keyDown && down) {
                this.toggle();
                this.keyDown = true;
            } else if (!down) {
                this.keyDown = false;
            }
        } catch (Throwable ignored) {
        }
    }

    public void syncKeyBindState() {
        if (this.keycode == 0) {
            return;
        }
        try {
            this.keyDown = this.keycode >= 1000
                    ? MC.mouseDown(this.keycode - 1000)
                    : MC.keyDown(this.keycode);
        } catch (Throwable ignored) {
        }
    }

    public void enable() {
        if (this.isEnabled()) {
            return;
        }
        this.setEnabled(true);
        try {
            this.onEnable();
        } catch (Throwable t) {
            com.rottenapple.agent.RottenAppleAgent.status("module " + getName() + " onEnable failed: " + t);
        }
    }

    public void disable() {
        if (!this.isEnabled()) {
            return;
        }
        this.setEnabled(false);
        try {
            this.onDisable();
        } catch (Throwable t) {
            com.rottenapple.agent.RottenAppleAgent.status("module " + getName() + " onDisable failed: " + t);
        }
    }

    public String getInfo() {
        return "";
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return this.moduleName;
    }

    public ArrayList<Setting> getSettings() {
        return this.settings;
    }

    public void registerSetting(Setting setting) {
        this.settings.add(setting);
    }

    public Module.Category moduleCategory() {
        return this.moduleCategory;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    /** Per-poll main logic. Replaces Raven's Forge @SubscribeEvent handlers. */
    public void onTick() {
    }

    public void toggle() {
        if (this.isEnabled()) {
            this.disable();
        } else {
            this.enable();
        }
        ModuleManager.saveSoon();
    }

    public void guiUpdate() {
    }

    public void guiButtonToggled(ButtonSetting b) {
    }

    public void onSlide(SliderSetting setting) {
    }

    public int getKeycode() {
        return this.keycode;
    }

    public void setBind(int keybind) {
        this.keycode = keybind;
    }

    public enum Category {
        combat,
        player,
        render;
    }
}
