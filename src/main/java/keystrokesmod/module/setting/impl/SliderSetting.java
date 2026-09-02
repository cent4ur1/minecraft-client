package keystrokesmod.module.setting.impl;

import com.google.gson.JsonObject;
import keystrokesmod.event.PostSetSliderEvent;
import keystrokesmod.module.setting.Setting;
import net.minecraftforge.common.MinecraftForge;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class SliderSetting extends Setting {
    private String settingName;
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
    private String[] legacyProfileKeys;
    private boolean allowOutOfRangeProfileValues;

    public SliderSetting(GroupSetting groupSetting, String settingName, double defaultValue, double min, double max, double intervals) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.settingName = settingName;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.intervals = intervals;
        this.isString = false;
        this.legacyProfileKeys = new String[0];
    }

    public SliderSetting(String settingName, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
    }

    public SliderSetting(String settingName, double defaultValue, double min, double max, double intervals, String... legacyProfileKeys) {
        this(settingName, defaultValue, min, max, intervals);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this(groupSetting, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, String suffix, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
    }

    public SliderSetting(String settingName, String suffix, double defaultValue, double min, double max, double intervals, String... legacyProfileKeys) {
        this(settingName, suffix, defaultValue, min, max, intervals);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, String minString, double defaultValue, double min, double max, double intervals) {
        this(groupSetting, settingName, suffix, defaultValue, min, max, intervals);
        this.minString = minString;
    }

    public SliderSetting(String settingName, String suffix, String minString, double defaultValue, double min, double max, double intervals) {
        this((GroupSetting) null, settingName, suffix, minString, defaultValue, min, max, intervals);
    }

    public SliderSetting(String settingName, boolean canBeDisabled, int defaultValue, String[] options) {
        this(settingName, defaultValue, options);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, defaultValue, min, max, intervals);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals, String... legacyProfileKeys) {
        this(settingName, canBeDisabled, defaultValue, min, max, intervals);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(GroupSetting group, String settingName, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(group, settingName, defaultValue, min, max, intervals);
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(String settingName, String suffix, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, defaultValue, min, max, intervals);
        this.suffix = suffix;
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(String settingName, String suffix, String minString, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(settingName, suffix, defaultValue, min, max, intervals);
        this.minString = minString;
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, String minString, boolean canBeDisabled, double defaultValue, double min, double max, double intervals) {
        this(groupSetting, settingName, suffix, defaultValue, min, max, intervals);
        this.minString = minString;
        this.canBeDisabled = canBeDisabled;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, int defaultValue, String[] options) {
        super(settingName);
        this.groupSetting = groupSetting;
        this.settingName = settingName;
        this.options = sanitizeOptions(options);
        this.defaultValue = correctValue(defaultValue, 0, this.options.length - 1);
        this.min = 0;
        this.max = this.options.length - 1;
        this.intervals = 1;
        this.isString = true;
        this.legacyProfileKeys = new String[0];
    }

    public SliderSetting(String settingName, int defaultValue, String[] options) {
        this((GroupSetting) null, settingName, defaultValue, options);
    }

    public SliderSetting(String settingName, int defaultValue, String[] options, String... legacyProfileKeys) {
        this((GroupSetting) null, settingName, defaultValue, options);
        this.legacyProfileKeys = legacyProfileKeys != null ? legacyProfileKeys : new String[0];
    }

    public SliderSetting(String settingName, String suffix, int defaultValue, String[] options) {
        this((GroupSetting) null, settingName, defaultValue, options);
        this.suffix = suffix;
    }

    public SliderSetting(GroupSetting groupSetting, String settingName, String suffix, int defaultValue, String[] options) {
        this(groupSetting, settingName, defaultValue, options);
        this.suffix = suffix;
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

    /** Replaces a string slider's choices while retaining the selected choice by name. */
    public void setOptions(String[] newOptions) {
        String selectedOption = getSelectedOption();
        this.options = sanitizeOptions(newOptions);
        this.min = 0;
        this.max = this.options.length - 1;
        this.intervals = 1;
        this.isString = true;

        if (!setSelectedOption(selectedOption)) {
            setValue(0);
        }
    }

    public String getName() {
        return this.settingName;
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

    public void setValueWithEvent(double newValue) {
        double prev = this.defaultValue;
        MinecraftForge.EVENT_BUS.post(new PostSetSliderEvent(prev, this.setValue(newValue)));
    }

    public void setValueRaw(double n) {
        this.defaultValue = n;
    }

    public void setValueRawWithEvent(double n) {
        double prev = this.defaultValue;
        this.defaultValue = n;
        MinecraftForge.EVENT_BUS.post(new PostSetSliderEvent(prev, n));
    }

    public SliderSetting allowOutOfRangeProfileValues() {
        this.allowOutOfRangeProfileValues = true;
        return this;
    }

    public static double correctValue(double v, double i, double a) {
        v = Math.max(i, v);
        v = Math.min(a, v);
        return v;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public static double roundToInterval(double v, int p) {
        if (p < 0) {
            return 0.0D;
        } else {
            BigDecimal bd = new BigDecimal(v);
            bd = bd.setScale(p, RoundingMode.HALF_UP);
            return bd.doubleValue();
        }
    }

    @Override
    public void loadProfile(JsonObject data) {
        String profileKey = getProfileKey();
        String legacyKey = getName();
        String key = null;
        if (data.has(profileKey)) {
            key = profileKey;
        }
        else if (data.has(legacyKey)) {
            key = legacyKey;
        }
        else {
            for (String legacyProfileKey : legacyProfileKeys) {
                if (data.has(legacyProfileKey)) {
                    key = legacyProfileKey;
                    break;
                }
            }
        }
        if (key != null && data.has(key) && data.get(key).isJsonPrimitive()) {
            if (isString && data.getAsJsonPrimitive(key).isString()) {
                String selectedOption = data.getAsJsonPrimitive(key).getAsString();
                if (canBeDisabled && "Disabled".equalsIgnoreCase(selectedOption)) {
                    setValueRaw(-1);
                }
                else if (!setSelectedOption(selectedOption)) {
                    setValue(0);
                }
                return;
            }

            double newValue = defaultValue;
            try {
                newValue = data.getAsJsonPrimitive(key).getAsDouble();
            }
            catch (Exception e) {

            }
            if (newValue == -1) {
                setValueRaw(newValue);
                return;
            }
            if (allowOutOfRangeProfileValues) {
                setValueRaw(newValue);
            }
            else {
                setValue(newValue);
            }
        }
    }

    private static String[] sanitizeOptions(String[] options) {
        return options == null || options.length == 0 ? new String[]{ "" } : options.clone();
    }
}
