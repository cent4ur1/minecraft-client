package com.rottenapple.module;

import com.rottenapple.client.RottenAppleConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Minimal properties persistence (~/.rottenapple.cfg). */
public final class ConfigManager {
    private ConfigManager() {
    }

    private static File file() {
        return new File(System.getProperty("user.home"), ".rottenapple.cfg");
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("client.opacity", Float.toString(RottenAppleConfig.menuOpacity));
            p.setProperty("client.toggleSprint", Boolean.toString(RottenAppleConfig.toggleSprint));
            p.setProperty("client.fullbright", Boolean.toString(RottenAppleConfig.fullbright));
            p.setProperty("client.showFps", Boolean.toString(RottenAppleConfig.showFps));
            p.setProperty("client.accent", Integer.toString(RottenAppleConfig.accentRgb));
            for (Module m : ModuleManager.modules) {
                String pre = "module." + m.getName() + ".";
                p.setProperty(pre + "enabled", Boolean.toString(m.isEnabled()));
                p.setProperty(pre + "bind", Integer.toString(m.getKeycode()));
                for (Setting s : m.getSettings()) {
                    String key = pre + s.getProfileKey();
                    if (s instanceof ButtonSetting) {
                        ButtonSetting b = (ButtonSetting) s;
                        if (!b.isMethodButton) p.setProperty(key, Boolean.toString(b.isToggled()));
                    } else if (s instanceof SliderSetting) {
                        SliderSetting sl = (SliderSetting) s;
                        if (sl.isString) {
                            String opt = sl.getSelectedOption();
                            if (opt != null) p.setProperty(key, opt);
                        } else {
                            p.setProperty(key, Double.toString(sl.getInput()));
                        }
                    } else if (s instanceof ColorSetting) {
                        ColorSetting c = (ColorSetting) s;
                        p.setProperty(key, c.getRed() + "," + c.getGreen() + "," + c.getBlue() + "," + c.getAlpha());
                    } else if (s instanceof BlockListSetting) {
                        BlockListSetting bl = (BlockListSetting) s;
                        p.setProperty(key, join(bl.getBlocks()));
                    } else if (s instanceof KeySetting) {
                        p.setProperty(key, Integer.toString(((KeySetting) s).getKey()));
                    }
                }
            }
            FileOutputStream out = new FileOutputStream(file());
            try {
                p.store(out, "RottenApple");
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void load() {
        try {
            File f = file();
            if (!f.isFile()) return;
            Properties p = new Properties();
            FileInputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            RottenAppleConfig.menuOpacity = f(p, "client.opacity", RottenAppleConfig.menuOpacity);
            RottenAppleConfig.clamp();
            RottenAppleConfig.toggleSprint = b(p, "client.toggleSprint", RottenAppleConfig.toggleSprint);
            RottenAppleConfig.fullbright = b(p, "client.fullbright", RottenAppleConfig.fullbright);
            RottenAppleConfig.showFps = b(p, "client.showFps", RottenAppleConfig.showFps);
            try {
                if (p.containsKey("client.accent")) {
                    RottenAppleConfig.accentRgb = Integer.parseInt(p.getProperty("client.accent").trim()) & 0xFFFFFF;
                }
            } catch (Throwable ignored) {
            }
            for (Module m : ModuleManager.modules) {
                String pre = "module." + m.getName() + ".";
                if (p.containsKey(pre + "bind")) {
                    try {
                        m.setBind(Integer.parseInt(p.getProperty(pre + "bind").trim()));
                    } catch (Throwable ignored) {
                    }
                }
                for (Setting s : m.getSettings()) {
                    loadSetting(p, pre + s.getProfileKey(), s);
                }
                if (b(p, pre + "enabled", false)) {
                    try {
                        m.enable();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    m.guiUpdate();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadSetting(Properties p, String key, Setting s) {
        try {
            if (!p.containsKey(key)) return;
            String v = p.getProperty(key);
            if (s instanceof ButtonSetting) {
                ButtonSetting b = (ButtonSetting) s;
                if (!b.isMethodButton) b.setEnabled(Boolean.parseBoolean(v.trim()));
            } else if (s instanceof SliderSetting) {
                SliderSetting sl = (SliderSetting) s;
                if (sl.isString) {
                    if (!sl.setSelectedOption(v.trim())) {
                        try {
                            sl.setValue(Double.parseDouble(v.trim()));
                        } catch (Throwable ignored) {
                        }
                    }
                } else {
                    sl.setValue(Double.parseDouble(v.trim()));
                }
            } else if (s instanceof ColorSetting) {
                ColorSetting c = (ColorSetting) s;
                String[] parts = v.split(",");
                if (parts.length >= 3) {
                    int a = parts.length >= 4 ? parse(parts[3], 255) : c.getAlpha();
                    c.setColor(parse(parts[0], 0), parse(parts[1], 0), parse(parts[2], 0), a);
                }
            } else if (s instanceof BlockListSetting) {
                ((BlockListSetting) s).setBlocks(split(v));
            } else if (s instanceof KeySetting) {
                try {
                    ((KeySetting) s).setKey(Integer.parseInt(v.trim()));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(";;");
            sb.append(id);
        }
        return sb.toString();
    }

    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        for (String part : v.split(";;")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean b(Properties p, String k, boolean def) {
        try {
            return p.containsKey(k) ? Boolean.parseBoolean(p.getProperty(k).trim()) : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static float f(Properties p, String k, float def) {
        try {
            return p.containsKey(k) ? Float.parseFloat(p.getProperty(k).trim()) : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int parse(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }
}
