package com.rottenapple.module;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric slider or mode selector (mirrors Raven's SliderSetting).
 * getInput() returns the current value (mode index for string sliders).
 */
public class SliderSetting extends Setting {
    private String[] options = null;
    private double defaultValue;
    private double max;
    private double min;
    private double intervals;
    public boolean isString;
    private String suffix = "";
    private String minString = null;
    public boolean canBeDisabled;
    public GroupSetting groupSetting;

    public SliderSetting(GroupSetting groupSetting, String settingName, double defaultValue, double min, double max, double intervals) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.intervals = intervals;
        this.isString = false;
    }

    public SliderSetting(String settingName, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this(groupSetting, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, defaultValue, min, max, intervals);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, int defaultValue, String[] options) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.options = sanitizeOptions(options);
        this.defaultValue = correctValue(defaultValue, 0, this.options.length - 1);
        this.min = 0;
        this.max = this.options.length - 1;
        this.intervals = 1;
        this.isString = true;
    }

    public SliderSetting(String settingName, int defaultValue, String[] options) {
        this((GroupSetting) null, settingName, defaultValue, options);
    }

    public SliderSetting(String settingName, boolean canBeDisabled, int defaultValue, String[] options) {
        this(settingName, defaultValue, options);
        this.canBeDisabled = canBeDisabled;
    }

    public String getSuffix() {
        return this.suffix;
    }

    public String getMinString() {
        return this.minString;
    }

    public void setMinString(String minString) {
        this.minString = minString;
    }

    public String[] getOptions() {
        return options;
    }

    public String getSelectedOption() {
        if (!isString || options == null || options.length == 0) {
            return null;
        }
        int index = (int) Math.max(0, Math.min(options.length - 1, Math.round(defaultValue)));
        return options[index];
    }

    public boolean setSelectedOption(String option) {
        if (!isString || option == null || options == null) {
            return false;
        }
        for (int i = 0; i < options.length; i++) {
            if (option.equals(options[i])) {
                setValue(i);
                return true;
            }
        }
        for (int i = 0; i < options.length; i++) {
            if (option.equalsIgnoreCase(options[i])) {
                setValue(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public String getProfileKey() {
        return groupSetting == null ? getName() : groupSetting.getName() + "." + getName();
    }

    public double getInput() {
        return roundToInterval(this.defaultValue, 4);
    }

    public double getMin() {
        return this.min;
    }

    public double getMax() {
        return this.max;
    }

    public double getInterval() {
        return this.intervals;
    }

    public double setValue(double newValue) {
        newValue = correctValue(newValue, this.min, this.max);
        newValue = (double) Math.round(newValue * (1.0D / this.intervals)) / (1.0D / this.intervals);
        return this.defaultValue = newValue;
    }

    public void setValueRaw(double n) {
        this.defaultValue = n;
    }

    public static double correctValue(double v, double i, double a) {
        v = Math.max(i, v);
        v = Math.min(a, v);
        return v;
    }

    public static double roundToInterval(double v, int p) {
        if (p < 0) {
            return 0.0D;
        }
        BigDecimal bd = new BigDecimal(v);
        bd = bd.setScale(p, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private static String[] sanitizeOptions(String[] options) {
        return options == null || options.length == 0 ? new String[]{ "" } : options.clone();
    }
}
