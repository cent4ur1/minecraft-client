package com.rottenapple.module;

/** Toggle or action button (mirrors Raven's ButtonSetting). */
public class ButtonSetting extends Setting {
    private boolean isEnabled;
    public boolean isMethodButton;
    private Runnable method;
    public GroupSetting group;

    public ButtonSetting(String name, boolean isEnabled) {
        super(name);
        this.isEnabled = isEnabled;
        this.isMethodButton = false;
    }

    public ButtonSetting(String name, boolean isEnabled, String... legacyProfileKeys) {
        this(name, isEnabled);
    }

    public ButtonSetting(GroupSetting group, String name, boolean isEnabled) {
        super(name);
        this.group = group;
        this.isEnabled = isEnabled;
        this.isMethodButton = false;
    }

    public ButtonSetting(GroupSetting group, String name, boolean isEnabled, String... legacyProfileKeys) {
        this(group, name, isEnabled);
    }

    public ButtonSetting(String name, Runnable method) {
        super(name);
        this.isEnabled = false;
        this.isMethodButton = true;
        this.method = method;
    }

    public void runMethod() {
        if (method != null) {
            method.run();
        }
    }

    @Override
    public String getProfileKey() {
        return group == null ? getName() : group.getName() + "." + getName();
    }

    public boolean isToggled() {
        return this.isEnabled;
    }

    public void toggle() {
        this.isEnabled = !this.isEnabled;
    }

    public void enable() {
        this.isEnabled = true;
    }

    public void disable() {
        this.isEnabled = false;
    }

    public void setEnabled(boolean b) {
        this.isEnabled = b;
    }
}
