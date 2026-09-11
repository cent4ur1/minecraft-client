package com.rottenapple.module;

/** Base setting: name + visibility flag (mirrors Raven's Setting). */
public abstract class Setting {
    public String name;
    public boolean visible = true;

    public Setting(String name) {
        this.name = name;
    }

    public void setVisible(boolean visible, Module module) {
        this.visible = visible;
    }

    public String getName() {
        return this.name;
    }

    public String getProfileKey() {
        return this.name;
    }
}
