package com.rottenapple.client.gui;

import com.rottenapple.bridge.GameBridge;
import com.rottenapple.client.RottenAppleConfig;
import com.rottenapple.module.BlockListSetting;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.ColorSetting;
import com.rottenapple.module.DescriptionSetting;
import com.rottenapple.module.GroupSetting;
import com.rottenapple.module.ItemListSetting;
import com.rottenapple.module.KeySetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.ModuleManager;
import com.rottenapple.module.Setting;
import com.rottenapple.module.SliderSetting;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "RottenApple Client" menu: Client tab (original utilities) + Modules tab
 * (ported Raven modules with full settings, binds and persistence).
 * Pure Swing; no game imports.
 */
public class RottenAppleGui extends JFrame {

    private static volatile RottenAppleGui instance;
    private static volatile boolean lafReady;

    private static final Color BG = new Color(0x1E1E24);
    private static final Color BG2 = new Color(0x141419);
    private static final Color FG = new Color(0xEDEDF2);
    private static final Color DIM = new Color(0xA8A8B3);
    private static final Color ACCENT = new Color(0xE5484D);

    // Client tab widgets.
    private ToggleSquare sprintBox;
    private ToggleSquare fullbrightBox;
    private ToggleSquare fpsBox;
    private final JSlider opacitySlider = new JSlider(40, 100);
    private final JLabel fpsLabel = new JLabel("fps: -");

    // Modules tab widgets.
    private final JPanel moduleListPanel = new JPanel();
    private final JPanel detailPanel = new JPanel();
    private final JScrollPane detailScroll = new JScrollPane(detailPanel);
    private Module selected;
    private String lastVisSig = "";
    private final List<ModuleRow> moduleRows = new ArrayList<ModuleRow>();
    private javax.swing.JTable playersTable;
    private javax.swing.table.DefaultTableModel playersModel;

    private static class ModuleRow {
        Module module;
        ToggleSquare box;
        JLabel info;
    }

    public RottenAppleGui() {
        super("RottenApple Client");
        setSize(680, 480);
        setResizable(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Client", buildClientTab());
        tabs.addTab("Modules", buildModulesTab());
        tabs.addTab("Players", buildPlayersTab());
        theme(tabs);
        add(tabs, BorderLayout.CENTER);

        applyOpacity();
        bindKeys();
        setLocationRelativeTo(null);

        new Timer(500, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateFps();
                refreshModuleRows();
                refreshPlayers();
            }
        }).start();
    }

    // ---------- Client tab (original utilities) ----------

    private JComponent buildClientTab() {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        GradientPanel headerBg = new GradientPanel(new BorderLayout());
        JLabel header = new JLabel("RottenApple Client", SwingConstants.CENTER);
        header.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        headerBg.add(header, BorderLayout.CENTER);
        center.add(headerBg);

        sprintBox = toggledSquare(RottenAppleConfig.toggleSprint, new Runnable() {
            @Override
            public void run() {
                RottenAppleConfig.toggleSprint = sprintBox.isOn();
                ModuleManager.saveSoon();
            }
        });
        fullbrightBox = toggledSquare(RottenAppleConfig.fullbright, new Runnable() {
            @Override
            public void run() {
                RottenAppleConfig.fullbright = fullbrightBox.isOn();
                ModuleManager.saveSoon();
            }
        });
        fpsBox = toggledSquare(RottenAppleConfig.showFps, new Runnable() {
            @Override
            public void run() {
                RottenAppleConfig.showFps = fpsBox.isOn();
                ModuleManager.saveSoon();
            }
        });
        center.add(toggleRow(sprintBox, "Toggle Sprint"));
        center.add(toggleRow(fullbrightBox, "Fullbright"));
        center.add(toggleRow(fpsBox, "Show FPS"));

        center.add(new JLabel("Menu opacity:"));
        JButton accentBtn = new JButton("Accent color");
        accentBtn.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Color chosen = JColorChooser.showDialog(
                        RottenAppleGui.this, "Accent color", accent());
                if (chosen != null) {
                    RottenAppleConfig.accentRgb = chosen.getRGB() & 0xFFFFFF;
                    ModuleManager.saveSoon();
                    refreshDetail();
                    theme(getContentPane());
                    repaint();
                }
            }
        });
        center.add(accentBtn);
        opacitySlider.setValue(Math.round(RottenAppleConfig.menuOpacity * 100));
        opacitySlider.setPaintTicks(false);
        opacitySlider.setUI(new ThickSliderUI(opacitySlider));
        opacitySlider.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                RottenAppleConfig.menuOpacity = opacitySlider.getValue() / 100f;
                RottenAppleConfig.clamp();
                applyOpacity();
                ModuleManager.saveSoon();
            }
        });
        center.add(opacitySlider);
        center.add(fpsLabel);
        center.add(new JLabel("Menu toggle: P or INSERT (Fn+Return on Mac)"));
        theme(center);
        return center;
    }

    /**
     * Lunar's locked-down module graph breaks Apple's Aqua look-and-feel
     * (IllegalAccessError on sun.awt). Metal renders fine everywhere.
     * Must run BEFORE the first frame is constructed.
     */
    private static void ensureLookAndFeel() {
        if (lafReady) return;
        lafReady = true;
        try {
            javax.swing.UIManager.setLookAndFeel(
                    javax.swing.UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Throwable ignored) {
            // fall back to whatever default survives
        }
    }

    private void applyOpacity() {
        try {
            setOpacity(RottenAppleConfig.menuOpacity);
        } catch (Exception e) {
            opacitySlider.setEnabled(false);
        }
    }

    private void updateFps() {
        if (!RottenAppleConfig.showFps) {
            fpsLabel.setText("fps: hidden");
            return;
        }
        String fps = GameBridge.getFps();
        fpsLabel.setText(fps == null ? "fps: n/a" : "fps: " + fps);
    }

    // ---------- Modules tab ----------

    private JComponent buildModulesTab() {
        moduleListPanel.setLayout(new BoxLayout(moduleListPanel, BoxLayout.Y_AXIS));
        for (Module m : ModuleManager.modules) {
            moduleListPanel.add(buildModuleRow(m));
        }
        JScrollPane listScroll = new JScrollPane(moduleListPanel);
        listScroll.setPreferredSize(new Dimension(220, 100));

        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        if (!ModuleManager.modules.isEmpty()) {
            selected = ModuleManager.modules.get(0);
        }
        refreshDetail();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setDividerLocation(230);
        split.setResizeWeight(0.0);
        theme(split);
        return split;
    }

    private JComponent buildModuleRow(final Module m) {
        JPanel row = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        final ToggleSquare box = new ToggleSquare(m.isEnabled()) {
            @Override
            protected void onToggle(boolean on) {
                if (on) {
                    m.enable();
                } else {
                    m.disable();
                }
                ModuleManager.saveSoon();
                selectModule(m);
            }
        };
        JLabel nameLabel = new JLabel(m.getName());
        nameLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                selectModule(m);
            }
        });
        left.add(box);
        left.add(nameLabel);
        final JLabel info = new JLabel(infoText(m));
        info.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        row.add(left, BorderLayout.CENTER);
        row.add(info, BorderLayout.EAST);
        info.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                selectModule(m);
            }
        });
        ModuleRow r = new ModuleRow();
        r.module = m;
        r.box = box;
        r.info = info;
        moduleRows.add(r);
        theme(row);
        return row;
    }

    private static String infoText(Module m) {
        try {
            String info = m.getInfo();
            return info == null ? "" : info;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void refreshModuleRows() {
        for (ModuleRow r : moduleRows) {
            if (r.box.isOn() != r.module.isEnabled()) {
                r.box.setOn(r.module.isEnabled());
            }
            String t = infoText(r.module);
            if (!t.equals(r.info.getText())) {
                r.info.setText(t);
            }
        }
    }

    private void selectModule(Module m) {
        selected = m;
        refreshDetail();
    }

    /** Rebuilds the detail panel only when setting visibility actually changed. */
    private void refreshDetailIfNeeded() {
        if (selected == null) {
            refreshDetail();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Setting s : selected.getSettings()) {
            sb.append(s.visible ? '1' : '0');
        }
        if (!sb.toString().equals(lastVisSig)) {
            refreshDetail();
        }
    }

    private void refreshDetail() {
        detailPanel.removeAll();
        if (selected == null) {
            detailPanel.add(new JLabel("No module"));
        } else {
            detailPanel.add(buildDetailHeader(selected));
            detailPanel.add(new JSeparator());
            for (Setting s : new ArrayList<Setting>(selected.getSettings())) {
                if (!s.visible) continue;
                JComponent c = buildSettingComponent(selected, s);
                if (c != null) detailPanel.add(c);
            }
            detailPanel.add(Box.createVerticalGlue());
        }
        theme(detailPanel);
        StringBuilder sig = new StringBuilder();
        if (selected != null) {
            for (Setting s : selected.getSettings()) {
                sig.append(s.visible ? '1' : '0');
            }
        }
        lastVisSig = sig.toString();
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private JComponent buildDetailHeader(final Module m) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel name = new JLabel(m.getName() + "  ");
        name.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        name.setForeground(accent());
        final ToggleSquare enabledBox = new ToggleSquare(m.isEnabled()) {
            @Override
            protected void onToggle(boolean on) {
                if (on) {
                    m.enable();
                } else {
                    m.disable();
                }
                ModuleManager.saveSoon();
                refreshModuleRows();
            }
        };
        final JButton bind = new JButton("Bind: " + bindName(m.getKeycode()));
        bind.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                bind.setText("press key... (ESC cancels)");
                final java.awt.KeyEventDispatcher[] holder = new java.awt.KeyEventDispatcher[1];
                holder[0] = new java.awt.KeyEventDispatcher() {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent e) {
                        if (e.getID() != KeyEvent.KEY_PRESSED) {
                            return false;
                        }
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .removeKeyEventDispatcher(holder[0]);
                            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                                m.setBind(0);
                            } else {
                                Integer lwjgl = AWT_TO_LWJGL.get(Integer.valueOf(e.getKeyCode()));
                                m.setBind(lwjgl == null ? 0 : lwjgl.intValue());
                            }
                            ModuleManager.saveSoon();
                            refreshDetail();
                            return true;
                        }
                    };
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .addKeyEventDispatcher(holder[0]);
                    autoDisarm(holder[0]);
                }
            });
            p.add(name);
            p.add(bind);
        theme(p);
        return p;
    }

    private JComponent buildSettingComponent(final Module m, final Setting s) {
        if (s instanceof DescriptionSetting) {
            JLabel l = new JLabel("<html><i>" + escape(s.getName()) + "</i></html>");
            theme(l);
            return l;
        }
        if (s instanceof GroupSetting) {
            JLabel l = new JLabel(s.getName());
            l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            theme(l);
            return l;
        }
        if (s instanceof ButtonSetting) {
            final ButtonSetting b = (ButtonSetting) s;
            if (b.isMethodButton) {
                JButton btn = new JButton(b.getName());
                btn.addActionListener(new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        b.runMethod();
                    }
                });
                theme(btn);
                return btn;
            }
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            ToggleSquare box = new ToggleSquare(b.isToggled()) {
                @Override
                protected void onToggle(boolean on) {
                    b.setEnabled(on);
                    m.guiUpdate();
                    ModuleManager.saveSoon();
                    refreshDetailIfNeeded();
                }
            };
            bp.add(box);
            bp.add(new JLabel(b.getName()));
            theme(bp);
            return bp;
        }
        if (s instanceof SliderSetting) {
            final SliderSetting sl = (SliderSetting) s;
            if (sl.isString) {
                JPanel p = new JPanel(new BorderLayout());
                JLabel name = new JLabel(sl.getName());
                String[] opts = sl.getOptions() == null ? new String[]{""} : sl.getOptions();
                final JComboBox<String> combo = new JComboBox<String>(opts);
                combo.setSelectedIndex((int) Math.max(0, Math.min(opts.length - 1, Math.round(sl.getInput()))));
                combo.addActionListener(new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        sl.setSelectedOption((String) combo.getSelectedItem());
                        m.guiUpdate();
                        ModuleManager.saveSoon();
                        refreshDetailIfNeeded();
                    }
                });
                p.add(name, BorderLayout.WEST);
                p.add(combo, BorderLayout.EAST);
                theme(p);
                return p;
            }
            JPanel p = new JPanel(new BorderLayout());
            final JLabel value = new JLabel(sliderText(sl));
            JLabel name = new JLabel(sl.getName());
            double steps = Math.round((sl.getMax() - sl.getMin()) / sl.getInterval());
            if (steps < 1) steps = 1;
            final int n = (int) steps;
            final JSlider slider = new JSlider(0, n);
            slider.setUI(new ThickSliderUI(slider));
            slider.setValue((int) Math.round((sl.getInput() - sl.getMin()) / sl.getInterval()));
            slider.addChangeListener(new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    sl.setValue(sl.getMin() + slider.getValue() * sl.getInterval());
                    value.setText(sliderText(sl));
                    m.guiUpdate();
                    ModuleManager.saveSoon();
                    refreshDetailIfNeeded();
                }
            });
            p.add(name, BorderLayout.WEST);
            p.add(slider, BorderLayout.CENTER);
            p.add(value, BorderLayout.EAST);
            theme(p);
            return p;
        }
        if (s instanceof ColorSetting) {
            final ColorSetting c = (ColorSetting) s;
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel name = new JLabel(c.getName());
            final JButton swatch = new JButton("      ");
            swatch.setBackground(new Color(c.getRed(), c.getGreen(), c.getBlue()));
            swatch.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Color chosen = JColorChooser.showDialog(
                            RottenAppleGui.this, c.getName(),
                            new Color(c.getRed(), c.getGreen(), c.getBlue()));
                    if (chosen != null) {
                        c.setColor(chosen.getRed(), chosen.getGreen(), chosen.getBlue());
                        swatch.setBackground(chosen);
                        ModuleManager.saveSoon();
                    }
                }
            });
            p.add(name);
            p.add(swatch);
            theme(p);
            return p;
        }
        if (s instanceof KeySetting) {
            final KeySetting ks = (KeySetting) s;
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JLabel name = new JLabel(ks.getName());
            final JButton bindKey = new JButton("Key: " + bindName(ks.getKey()));
            bindKey.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    bindKey.setText("press key... (ESC cancels)");
                    final java.awt.KeyEventDispatcher[] holder = new java.awt.KeyEventDispatcher[1];
                    holder[0] = new java.awt.KeyEventDispatcher() {
                        @Override
                        public boolean dispatchKeyEvent(KeyEvent e) {
                            if (e.getID() != KeyEvent.KEY_PRESSED) {
                                return false;
                            }
                            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .removeKeyEventDispatcher(holder[0]);
                            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                                ks.setKey(0);
                            } else {
                                Integer lwjgl = AWT_TO_LWJGL.get(Integer.valueOf(e.getKeyCode()));
                                ks.setKey(lwjgl == null ? 0 : lwjgl.intValue());
                            }
                            ModuleManager.saveSoon();
                            refreshDetail();
                            return true;
                        }
                    };
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .addKeyEventDispatcher(holder[0]);
                    autoDisarm(holder[0]);
                }
            });
            p.add(name);
            p.add(bindKey);
            theme(p);
            return p;
        }
        if (s instanceof BlockListSetting) {
            final BlockListSetting bl = (BlockListSetting) s;
            JPanel p = new JPanel(new BorderLayout());
            JLabel name = new JLabel(s.getName() + " (one id per line, e.g. minecraft:stone)");
            final JTextArea area = new JTextArea(joinLines(bl.getBlocks()), 4, 24);
            area.getDocument().addDocumentListener(new DocumentListener() {
                private void apply() {
                    List<String> ids = new ArrayList<String>();
                    for (String line : area.getText().split("\\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !ids.contains(t)) ids.add(t);
                    }
                    bl.setBlocks(ids);
                    ModuleManager.saveSoon();
                }

                @Override
                public void insertUpdate(DocumentEvent e) {
                    apply();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    apply();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    apply();
                }
            });
            JScrollPane sp = new JScrollPane(area);
            p.add(name, BorderLayout.NORTH);
            p.add(sp, BorderLayout.CENTER);
            theme(p);
            return p;
        }
        return null;
    }

    private static String sliderText(SliderSetting sl) {
        double v = sl.getInput();
        if (sl.getMinString() != null && v == sl.getMin()) {
            return sl.getMinString();
        }
        String t = v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(RavenUtil_round(v));
        return t + sl.getSuffix();
    }

    private static double RavenUtil_round(double v) {
        return com.rottenapple.util.RavenUtil.round(v, 2);
    }

    private static String joinLines(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(id);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- binds ----------

    private static final Map<Integer, Integer> AWT_TO_LWJGL = new HashMap<Integer, Integer>();
    private static final Map<Integer, String> LWJGL_NAMES = new HashMap<Integer, String>();

    static {
        int[][] pairs = {
                {65, 30}, {66, 48}, {67, 46}, {68, 32}, {69, 18}, {70, 33}, {71, 34},
                {72, 35}, {73, 23}, {74, 36}, {75, 37}, {76, 38}, {77, 50}, {78, 49},
                {79, 24}, {80, 25}, {81, 16}, {82, 19}, {83, 31}, {84, 20}, {85, 22},
                {86, 47}, {87, 17}, {88, 45}, {89, 21}, {90, 44},
                {48, 11}, {49, 2}, {50, 3}, {51, 4}, {52, 5}, {53, 6}, {54, 7},
                {55, 8}, {56, 9}, {57, 10},
                {112, 59}, {113, 60}, {114, 61}, {115, 62}, {116, 63}, {117, 64},
                {118, 65}, {119, 66}, {120, 67}, {121, 68}, {122, 87}, {123, 88},
                {32, 57}, {27, 1}, {9, 15}, {10, 28}, {16, 42}, {17, 29}, {18, 56},
                {20, 58}, {45, 12}, {61, 13}, {91, 26}, {93, 27}, {59, 39}, {222, 40},
                {192, 41}, {92, 43}, {44, 51}, {46, 52}, {47, 53}, {8, 14},
                {38, 200}, {40, 208}, {37, 203}, {39, 205}, {155, 210}, {127, 211},
                {36, 199}, {35, 207}, {33, 201}, {34, 209}
        };
        for (int[] pr : pairs) {
            AWT_TO_LWJGL.put(Integer.valueOf(pr[0]), Integer.valueOf(pr[1]));
        }
        String[][] names = {
                {"1", "ESC"}, {"28", "ENTER"}, {"57", "SPACE"}, {"42", "LSHIFT"},
                {"29", "LCTRL"}, {"56", "LALT"}, {"210", "INSERT"}, {"211", "DELETE"},
                {"200", "UP"}, {"208", "DOWN"}, {"203", "LEFT"}, {"205", "RIGHT"}
        };
        for (String[] nm : names) {
            if (!nm[1].isEmpty()) {
                LWJGL_NAMES.put(Integer.valueOf(nm[0]), nm[1]);
            }
        }
        for (int i = 0; i < 26; i++) {
            LWJGL_NAMES.put(Integer.valueOf("30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44".split(",")[i]), String.valueOf((char) ('A' + i)));
        }
    }

    /** Drops a stuck bind-capture after 8s (e.g. keys pressed while game focused). */
    private void autoDisarm(final java.awt.KeyEventDispatcher d) {
        final Timer t = new Timer(8000, null);
        t.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                t.stop();
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removeKeyEventDispatcher(d);
                refreshDetail();
            }
        });
        t.setRepeats(false);
        t.start();
    }

    private static String bindName(int code) {
        if (code == 0) return "none";
        if (code >= 1000) return "M" + (code - 1000);
        String n = LWJGL_NAMES.get(Integer.valueOf(code));
        return n == null ? "#" + code : n;
    }

    private static Color accent() {
        return new Color(RottenAppleConfig.accentRgb);
    }

    /** Gradient strip (accent -> background). Kept non-opaque by theme(). */
    private static class GradientPanel extends JPanel {
        GradientPanel(java.awt.LayoutManager l) {
            super(l);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                Color a = accent();
                g2.setPaint(new java.awt.GradientPaint(
                        0, 0, a.darker().darker(), getWidth(), 0, getBackground()));
                g2.fillRect(0, 0, getWidth(), getHeight());
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** Thicker slider with accent fill (all our sliders are horizontal). */
    private static class ThickSliderUI extends javax.swing.plaf.basic.BasicSliderUI {
        ThickSliderUI(JSlider s) {
            super(s);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(16, 26);
        }

        @Override
        public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                int cy = trackRect.y + trackRect.height / 2;
                g2.setColor(new Color(0x2B2B33));
                g2.fillRect(trackRect.x, cy - 5, trackRect.width, 10);
                int fillW = thumbRect.x + thumbRect.width / 2 - trackRect.x;
                g2.setColor(accent());
                g2.fillRect(trackRect.x, cy - 5, Math.max(0, fillW), 10);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setColor(accent());
                g2.fillRoundRect(thumbRect.x, thumbRect.y,
                        thumbRect.width, thumbRect.height, 7, 7);
                g2.setColor(Color.WHITE);
                g2.drawRoundRect(thumbRect.x, thumbRect.y,
                        thumbRect.width - 1, thumbRect.height - 1, 7, 7);
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Tick-free toggle square: filled with the (customizable) accent color
     * when on, dark with a gray outline when off. No checkbox rendering.
     */
    private abstract static class ToggleSquare extends JComponent {
        private boolean on;

        ToggleSquare(boolean initial) {
            this.on = initial;
            setPreferredSize(new Dimension(18, 18));
            setMaximumSize(new Dimension(18, 18));
            setMinimumSize(new Dimension(18, 18));
            setToolTipText("toggle");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    setOn(!on);
                    onToggle(on);
                }
            });
        }

        void setOn(boolean v) {
            if (on != v) {
                on = v;
                repaint();
            }
        }

        boolean isOn() {
            return on;
        }

        protected abstract void onToggle(boolean on);

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                int s = Math.min(getWidth(), getHeight()) - 4;
                int x = (getWidth() - s) / 2;
                int y = (getHeight() - s) / 2;
                if (on) {
                    g2.setColor(accent());
                    g2.fillRoundRect(x, y, s, s, 5, 5);
                    g2.setColor(Color.WHITE);
                    g2.drawRoundRect(x, y, s - 1, s - 1, 5, 5);
                } else {
                    g2.setColor(new Color(0x141419));
                    g2.fillRoundRect(x, y, s, s, 5, 5);
                    g2.setColor(new Color(0x55555F));
                    g2.drawRoundRect(x, y, s - 1, s - 1, 5, 5);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    private static ToggleSquare toggledSquare(boolean initial, final Runnable onChange) {
        return new ToggleSquare(initial) {
            @Override
            protected void onToggle(boolean on) {
                onChange.run();
            }
        };
    }

    private static JPanel toggleRow(ToggleSquare box, String label) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        p.add(box);
        p.add(new JLabel(label));
        theme(p);
        return p;
    }

    private static void fadeIn(final JFrame f) {
        try {
            final float target = RottenAppleConfig.menuOpacity;
            f.setOpacity(0.15f);
            final Timer t = new Timer(15, null);
            t.addActionListener(new AbstractAction() {
                float o = 0.15f;

                @Override
                public void actionPerformed(ActionEvent e) {
                    o += 0.09f;
                    if (o >= target) {
                        try {
                            f.setOpacity(target);
                        } catch (Throwable ignored) {
                        }
                        t.stop();
                        return;
                    }
                    try {
                        f.setOpacity(o);
                    } catch (Throwable ignored) {
                        t.stop();
                    }
                }
            });
            t.start();
        } catch (Throwable ignored) {
        }
    }

    // ---------- theme / keys / lifecycle ----------

    /** Live player table fed by the Nametags data pipeline (no in-world draw). */
    private JComponent buildPlayersTab() {
        playersModel = new javax.swing.table.DefaultTableModel(
                new Object[]{"Tag", "Dist", "Armor"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        playersTable = new javax.swing.JTable(playersModel);
        playersTable.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(playersTable);
        theme(sp);
        return sp;
    }

    private void refreshPlayers() {
        if (playersModel == null) return;
        try {
            playersModel.setRowCount(0);
            com.rottenapple.module.impl.render.Nametags tags = null;
            for (Module m : ModuleManager.modules) {
                if (m instanceof com.rottenapple.module.impl.render.Nametags) {
                    tags = (com.rottenapple.module.impl.render.Nametags) m;
                    break;
                }
            }
            if (tags == null || !tags.isEnabled()) {
                playersModel.addRow(new Object[]{"(enable Nametags)", "", ""});
                return;
            }
            for (com.rottenapple.module.impl.render.Nametags.NametagState st : tags.getStates()) {
                String tag = com.rottenapple.util.RavenUtil.stripFormattingCodes(st.displayName);
                String dist = Math.round(Math.sqrt(st.distanceSq)) + "m";
                StringBuilder armor = new StringBuilder();
                for (String line : st.armorLines) {
                    if (armor.length() > 0) armor.append("; ");
                    armor.append(line);
                }
                playersModel.addRow(new Object[]{tag, dist, armor.toString()});
            }
            if (tags.getStates().isEmpty()) {
                playersModel.addRow(new Object[]{"(no players in view range)", "", ""});
            }
        } catch (Throwable ignored) {
        }
    }

    private static void theme(Component c) {
        if (c instanceof GradientPanel) {
            for (Component k : ((Container) c).getComponents()) {
                theme(k);
            }
            return;
        }
        if (c instanceof JComponent) {
            ((JComponent) c).setOpaque(true);
        }
        if (c instanceof JLabel || c instanceof JCheckBox || c instanceof JButton
                || c instanceof JPanel || c instanceof JTabbedPane || c instanceof JSplitPane) {
            c.setBackground(BG);
            c.setForeground(FG);
        }
        if (c instanceof JScrollPane) {
            c.setBackground(BG2);
        }
        if (c instanceof JTextArea || c instanceof JComboBox || c instanceof JSlider) {
            c.setBackground(BG2);
            c.setForeground(FG);
        }
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                theme(k);
            }
        }
    }

    private void bindKeys() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "ra-toggle");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "ra-toggle");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "ra-hide");
        getRootPane().getActionMap().put("ra-toggle", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggle();
            }
        });
        getRootPane().getActionMap().put("ra-hide", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setVisible(false);
            }
        });
    }

    /** Creates (if needed) and shows the menu. Safe from any thread. */
    public static void showGui() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ensureLookAndFeel();
                if (instance == null) {
                    instance = new RottenAppleGui();
                }
                instance.setVisible(true);
                instance.toFront();
                fadeIn(instance);
            }
        });
    }

    /** Toggles menu visibility. Safe from any thread. */
    public static void toggle() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ensureLookAndFeel();
                if (instance == null) {
                    instance = new RottenAppleGui();
                    instance.setVisible(true);
                    return;
                }
                boolean show = !instance.isVisible();
                instance.setVisible(show);
                if (show) {
                    instance.toFront();
                    fadeIn(instance);
                }
            }
        });
    }
}
