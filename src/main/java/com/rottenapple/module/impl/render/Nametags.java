package com.rottenapple.module.impl.render;

import com.rottenapple.agent.RottenAppleAgent;
import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.ColorSetting;
import com.rottenapple.module.DescriptionSetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import com.rottenapple.util.AntiBotUtil;
import com.rottenapple.util.RavenUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Nametags ported from Raven BS (settings + data pipeline identical).
 * LIMITATION: Raven draws in-world via RenderWorldLastEvent GL calls and hides
 * vanilla tags via a render-event cancel. A dependency-free polling agent has
 * no render-thread hook, so nothing is drawn in-world and vanilla tags remain.
 * The computed states below feed the client's Players tab instead.
 * Health display uses Raven Settings defaults (numbers, no heart symbol).
 */
public class Nametags extends Module {
    private SliderSetting scale;
    private SliderSetting font;
    private ButtonSetting autoScale;
    private ButtonSetting onlyRenderName;
    private SliderSetting background;
    private ButtonSetting bgBorder;
    private ButtonSetting showHealth;
    private ButtonSetting textShadow;
    private ButtonSetting showDistance;
    private ButtonSetting showInvis;
    private ButtonSetting showArmor;
    private ButtonSetting showEnchants;
    private ButtonSetting showDurability;
    private ButtonSetting showStackSize;
    private ButtonSetting renderSelf;
    private ButtonSetting hideVanilla;
    private ColorSetting friendColor;
    private ColorSetting enemyColor;

    private final List<NametagState> states = new ArrayList<NametagState>();
    private boolean noted;

    public static class NametagState {
        public Object player;
        public String displayName;
        public double distanceSq;
        public float baseScale;
        public float yOffset;
        public int relationshipColor = -1;
        public List<String> armorLines = new ArrayList<String>();
        public int totalItems;
    }

    private static final Comparator<NametagState> FAR_TO_NEAR = new Comparator<NametagState>() {
        @Override
        public int compare(NametagState a, NametagState b) {
            return Double.compare(b.distanceSq, a.distanceSq);
        }
    };

    public Nametags() {
        super("Nametags", Category.render, 0);
        this.registerSetting(background = new SliderSetting("Background", true, 0.5, 0.0, 1.0, 0.05));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.1, 2.0, 0.1));
        this.registerSetting(font = new SliderSetting("Font", 0,
                new String[]{"Default", "SansSerif", "Serif", "Monospaced"}));
        this.registerSetting(autoScale = new ButtonSetting("Auto-scale", false));
        this.registerSetting(onlyRenderName = new ButtonSetting("Only render name", false));
        this.registerSetting(renderSelf = new ButtonSetting("Render self", false));
        this.registerSetting(bgBorder = new ButtonSetting("Background border", false));
        this.registerSetting(showHealth = new ButtonSetting("Show health", false));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", false));
        this.registerSetting(hideVanilla = new ButtonSetting("Hide vanilla", true));
        this.registerSetting(showDistance = new ButtonSetting("Show distance", false));
        this.registerSetting(showInvis = new ButtonSetting("Show invis", true));
        this.registerSetting(friendColor = new ColorSetting("Friend color", 85, 255, 255));
        this.registerSetting(enemyColor = new ColorSetting("Enemy color", 255, 85, 85));
        this.registerSetting(new DescriptionSetting("Armor settings"));
        this.registerSetting(showArmor = new ButtonSetting("Show armor", false));
        this.registerSetting(showEnchants = new ButtonSetting("Show enchants", false));
        this.registerSetting(showDurability = new ButtonSetting("Show durability", false));
        this.registerSetting(showStackSize = new ButtonSetting("Show stack size", false));
    }

    @Override
    public void onEnable() {
        if (!noted) {
            noted = true;
            RottenAppleAgent.status("Nametags: in-world draw + vanilla-tag hiding need render hooks "
                    + "unavailable to a dep-free agent; live data feeds the Players tab.");
        }
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck() || MC.world() == null) {
            states.clear();
            return;
        }
        updateStates();
    }

    public List<NametagState> getStates() {
        return states;
    }

    private void updateStates() {
        Object viewer = MC.player();
        if (viewer == null) {
            states.clear();
            return;
        }
        double vpx = MC.posX(viewer), vpy = MC.posY(viewer), vpz = MC.posZ(viewer);
        boolean renderArmor = showArmor.isToggled();
        float baseScale = (float) scale.getInput() * 0.02F;
        states.clear();

        for (Object player : MC.players(MC.world())) {
            if (!shouldRenderNametag(player)) {
                continue;
            }
            double dx = MC.posX(player) - vpx;
            double dy = MC.posY(player) - vpy;
            double dz = MC.posZ(player) - vpz;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            float distance = (float) Math.sqrt(distanceSq);

            NametagState st = new NametagState();
            st.player = player;
            st.displayName = buildDisplayName(player, showDistance.isToggled(), distance);
            st.distanceSq = distanceSq;
            st.baseScale = baseScale;
            st.yOffset = (isSneaking(player) ? (MC.height(player) - 0.3F) : MC.height(player)) + 0.3F;
            st.relationshipColor = -1; // no Relationships module: never friended/enemy (Raven parity)

            if (renderArmor) {
                collectArmorLines(st, player);
            }
            states.add(st);
        }

        if (states.size() > 1) {
            Collections.sort(states, FAR_TO_NEAR);
        }
    }

    private boolean isSneaking(Object p) {
        try {
            return Boolean.TRUE.equals(MC.call(p, "isSneaking", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean shouldRenderNametag(Object player) {
        if (player == null) return false;
        if (player == MC.player()) {
            return renderSelf.isToggled() && MC.thirdPerson() != 0;
        }
        if (MC.isDead(player) || MC.deathTime(player) > 0) return false;
        if (!showInvis.isToggled() && MC.isInvisible(player)) return false;
        return !AntiBotUtil.isBot(player);
    }

    private String buildDisplayName(Object entity, boolean showDist, float distance) {
        String name;
        if (onlyRenderName.isToggled()) {
            String formatted = RavenUtil.getFirstColorCode(MC.displayNameFmt(entity));
            String color = (formatted.length() >= 2 && formatted.charAt(0) == '§') ? formatted : "";
            name = color + MC.entName(entity);
        } else {
            name = MC.displayNameFmt(entity);
        }

        if (showHealth.isToggled()) {
            name = appendHealth(name, entity);
        }

        if (showDist) {
            int dist = (int) distance;
            String distColor = dist <= 8 ? "§c" : (dist <= 15 ? "§6" : (dist <= 25 ? "§e" : "§7"));
            name = distColor + dist + "m§r " + name;
        }

        return name;
    }

    private String appendHealth(String name, Object entity) {
        float health = Math.max(0.0f, MC.getHealth(entity));
        float maxHealth = MC.getMaxHealth(entity);
        if (maxHealth <= 0.0f) maxHealth = 20.0f;
        double ratio = health / maxHealth;
        String color = ratio < 0.3 ? "§c" : (ratio < 0.5 ? "§6" : (ratio < 0.7 ? "§e" : "§a"));
        name = name + " " + color + RavenUtil.fastOneDecimal(health);
        float absorption = MC.getAbsorption(entity);
        if (absorption > 0) {
            name = name + " §6+" + RavenUtil.fastOneDecimal(absorption);
        }
        return name + "§r";
    }

    private void collectArmorLines(NametagState st, Object player) {
        // Slots mirror Raven: 0 held, 1 boots, 2 leggings, 3 chestplate, 4 helmet.
        for (int slot = 0; slot <= 4; slot++) {
            Object stack = MC.getEquipment(player, slot);
            if (stack == null) continue;
            st.totalItems++;
            if (!showEnchants.isToggled()) continue;
            String line = enchantLine(stack);
            if (showStackSize.isToggled() && MC.stackSize(stack) > 1) {
                line += " x" + MC.stackSize(stack);
            }
            if (showDurability.isToggled() && MC.damageable(stack) && MC.itemDamage(stack) > 0) {
                line += " " + MC.itemDamage(stack) + "/" + MC.maxDamage(stack);
            }
            if (!line.isEmpty()) st.armorLines.add(line);
        }
    }

    private String enchantLine(Object stack) {
        Object item = MC.stackItem(stack);
        int[] ids;
        String[] abbr;
        if (MC.isItemInstance(item, "ItemArmor")) {
            ids = RavenUtil.ARMOR_ENCHANT_IDS;
            abbr = RavenUtil.ARMOR_ENCHANT_ABBR;
        } else if (MC.isItemInstance(item, "ItemSword")) {
            ids = RavenUtil.SWORD_ENCHANT_IDS;
            abbr = RavenUtil.SWORD_ENCHANT_ABBR;
        } else if (MC.isItemInstance(item, "ItemBow")) {
            ids = RavenUtil.BOW_ENCHANT_IDS;
            abbr = RavenUtil.BOW_ENCHANT_ABBR;
        } else if (MC.isItemInstance(item, "ItemTool")) {
            ids = RavenUtil.TOOL_ENCHANT_IDS;
            abbr = RavenUtil.TOOL_ENCHANT_ABBR;
        } else {
            ids = RavenUtil.MISC_ENCHANT_IDS;
            abbr = RavenUtil.MISC_ENCHANT_ABBR;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            int level = MC.enchLevel(ids[i], stack);
            if (level <= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(abbr[i]).append(level);
        }
        return sb.toString();
    }
}
