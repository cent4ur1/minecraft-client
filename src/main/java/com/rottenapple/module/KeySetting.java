package com.rottenapple.module;

import com.rottenapple.bridge.MC;

/** Hold-to-activate key (mirrors Raven's KeySetting, minus scroll-wheel binds). */
public class KeySetting extends Setting {
    private int key;
    public GroupSetting group;

    public KeySetting(String name, int key) {
        super(name);
        this.key = key;
    }

    public KeySetting(GroupSetting group, String name, int key) {
        super(name);
        this.group = group;
        this.key = key;
    }

    public int getKey() {
        return this.key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    @Override
    public String getProfileKey() {
        return group == null ? getName() : group.getName() + "." + getName();
    }

    public boolean isPressed() {
        if (this.key == 0) {
            return false;
        }
        if (this.key >= 1000) {
            return MC.mouseDown(this.key - 1000);
        }
        return MC.keyDown(this.key);
    }
}
