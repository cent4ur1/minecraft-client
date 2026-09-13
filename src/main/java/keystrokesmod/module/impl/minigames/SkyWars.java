package keystrokesmod.module.impl.minigames;

import keystrokesmod.clickgui.animation.ScrollOffsetAnimation;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.event.UseItemEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemEnderPearl;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.List;

public class SkyWars extends Module {
    private static final long CLOSEST_ENEMY_ANIMATION_DURATION_MS = 100L;
    private static final long CLOSEST_ENEMY_FADE_DURATION_MS = 150L;
    private static final FloatBuffer ITEM_ALPHA_COLOR = BufferUtils.createFloatBuffer(4);

    public SliderSetting closestEnemy;
    public ButtonSetting strengthIndicator;
    public ButtonSetting onlyAuraHostileMobs;
    public ButtonSetting renderTimeWarp;

    public Map<EntityPlayer, Long> strengthPlayers = new HashMap<>();
    private Map<String, SpawnEggInfo> entitySpawnQueue = new LinkedHashMap<>(); // type name, spawn info
    private Map<Vec3, Long> timeWarpPositions = new LinkedHashMap<>(); // position when thrown, time when thrown
    public List<Integer> spawnedMobs = new ArrayList<>(); // entity id

    private final int STRENGTH_COLOR = new Color(255, 0, 0).getRGB();
    private final int TIME_WARP_COLOR = new Color(210, 0, 255, 64).getRGB();

    private String[] KILL_MESSAGES = new String[] {" by ", " to ", " with ", " of ", " from ", " knight ", " for "};

    private boolean thrownPearl;

    private float closestEnemyPosX = Float.NaN;
    private float closestEnemyPosY = Float.NaN;
    private float closestEnemyRelativePosX = Float.NaN;
    private float closestEnemyRelativePosY = Float.NaN;
    private final ScrollOffsetAnimation closestEnemyWidthAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final ScrollOffsetAnimation closestEnemyHeightAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final ScrollOffsetAnimation closestEnemyContentAnimation = new ScrollOffsetAnimation(CLOSEST_ENEMY_ANIMATION_DURATION_MS);
    private final SmoothFloatAnimation closestEnemyVisibilityAnimation = new SmoothFloatAnimation(CLOSEST_ENEMY_FADE_DURATION_MS);
    private boolean closestEnemySizeInitialized;
    private boolean closestEnemyVisibilityInitialized;
    private List<Integer> closestEnemySelection;
    private List<EnemyHudEntry> lastClosestEnemyEntries = Collections.emptyList();

    /**
     * A global variable used to determine if the current skywars game you are in is a teams mode or not
     */
    public static boolean isSkyWarsTeams = false;

    public SkyWars() {
        super("Sky Wars", category.minigames);
        this.liteModule = true;
        this.registerSetting(closestEnemy = new SliderSetting("Closest enemy", true, 1, 1, 5, 1));
        this.registerSetting(new ButtonSetting("Edit positions", () -> mc.displayGuiScreen(new EditPositionScreen())));
        this.registerSetting(onlyAuraHostileMobs = new ButtonSetting("Only aura hostile mobs", true));
        this.registerSetting(renderTimeWarp = new ButtonSetting("Render time warp", true));
        this.registerSetting(strengthIndicator = new ButtonSetting("Strength indicator", true));
    }

    @Override
    public void onDisable() {
        this.clear();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!strengthIndicator.isToggled() || !Utils.nullCheck() || strengthPlayers.isEmpty() || Utils.getSkyWarsStatus() != 2) {
            return;
        }
        int customMode = getCustomMode();
        if (customMode == 2) {
            return;
        }
        isSkyWarsTeams = customMode == 1;
        long duration = isSkyWarsTeams ? 2000 : 5000;
        ArrayList<EntityPlayer> keysList = new ArrayList<>(strengthPlayers.keySet());
        for (EntityPlayer entityPlayer : keysList) {
            long storedTime = strengthPlayers.get(entityPlayer);
            long timePassed = System.currentTimeMillis() - storedTime;
            if (timePassed < duration && !AntiBot.isBot(entityPlayer)) {
                continue;
            }
            strengthPlayers.remove(entityPlayer);
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        if (e.type == 2 || !Utils.nullCheck()) {
            return;
        }
        String stripped = Utils.stripColor(e.message.getUnformattedText());
        if (stripped.isEmpty()) {
            return;
        }
        if (stripped.equals("You will be warped back in 3 seconds!") && thrownPearl) {
            timeWarpPositions.put(new Vec3(mc.thePlayer.lastTickPosX, mc.thePlayer.lastTickPosY, mc.thePlayer.lastTickPosZ), System.currentTimeMillis());
            thrownPearl = false;
            return;
        }
        if (strengthIndicator.isToggled() && Utils.getSkyWarsStatus() == 2) {
            if (getCustomMode() == 2) { // lab, then no
                return;
            }
            if (stripped.endsWith(".") && Arrays.stream(KILL_MESSAGES).anyMatch(stripped::contains)) {
                String[] parts = stripped.split(" ");
                for (String part : parts) {
                    if (!part.endsWith(".")) {
                        continue;
                    }
                    String name = part.substring(0, part.length() - 1);
                    for (EntityPlayer entity : mc.theWorld.playerEntities) {
                        if (!entity.getName().trim().equals(name) || entity == mc.thePlayer) {
                            continue;
                        }
                        strengthPlayers.put(entity, System.currentTimeMillis());
                        break;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck() || Utils.getSkyWarsStatus() != 2) {
            return;
        }
        if (strengthIndicator.isToggled()) {
            for (EntityPlayer entityPlayer : strengthPlayers.keySet()) {
                if (AntiBot.isBot(entityPlayer)) {
                    continue;
                }
                RenderUtils.renderEntity(entityPlayer, 2, 0, 0, STRENGTH_COLOR, false);
            }
        }
        if (renderTimeWarp.isToggled()) {
            Iterator<Map.Entry<Vec3, Long>> iterator = this.timeWarpPositions.entrySet().iterator();
            long currentTime = System.currentTimeMillis();

            while (iterator.hasNext()) {
                Map.Entry<Vec3, Long> entry = iterator.next();
                Vec3 position = entry.getKey();
                long timeThrown = entry.getValue();

                if (currentTime - timeThrown >= 3050) {
                    iterator.remove();
                }
                else {
                    RenderUtils.drawPlayerBoundingBox(position, TIME_WARP_COLOR);
                }
            }
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            clear();
        }
        else {
            if (e.entity != null) {
                if (Utils.getSkyWarsStatus() != 2) {
                    return;
                }
                String entityClassName = e.entity.getClass().getSimpleName();
                if (entitySpawnQueue.containsKey(entityClassName)) {
                    Vec3 spawnPosition = new Vec3(e.entity.posX, e.entity.posY, e.entity.posZ);
                    SpawnEggInfo eggInfo = entitySpawnQueue.get(entityClassName);
                    if (eggInfo.spawnPos.distanceTo(spawnPosition) > 3 || Utils.timeBetween(mc.thePlayer.ticksExisted, eggInfo.tickSpawned) > 60) { // 3 seconds or not at spawn point then not own mob
                        return;
                    }
                    if (!entitySpawnQueue.remove(entityClassName, eggInfo)) {
                        return;
                    }
                    spawnedMobs.add(e.entity.getEntityId());
                }
            }
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement p = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (p.getPlacedBlockDirection() != 255 && p.getStack() != null && p.getStack().getItem() != null) {
                if (!(p.getStack().getItem() instanceof ItemMonsterPlacer)) {
                    return;
                }
                Class<? extends Entity> oclass = EntityList.stringToClassMapping.get(ItemMonsterPlacer.getEntityName(p.getStack()));
                if (oclass == null) {
                    return;
                }
                entitySpawnQueue.put(oclass.getSimpleName(), new SpawnEggInfo(p.getPosition(), mc.thePlayer.ticksExisted));
            }
        }
    }

    @SubscribeEvent
    public void onUseItem(UseItemEvent e) {
        if (e.usedItemStack != null && e.usedItemStack.getItem() instanceof ItemEnderPearl && Utils.getSkyWarsStatus() == 2) {
            ItemStack stack = e.usedItemStack;
            if (Utils.stripString(stack.getDisplayName()).equals("Time Warp Pearl")) {
                thrownPearl = true;
            }
            else {
                if (stack.getDisplayName().startsWith("§b§l")) {
                    List<String> toolTip = stack.getTooltip(mc.thePlayer, true);
                    if (toolTip != null && toolTip.size() > 1 && Utils.stripString(toolTip.get(1)).contains("Teleports you back to your")) {
                        thrownPearl = true;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()
                || mc.currentScreen != null || mc.gameSettings.showDebugInfo) {
            return;
        }

        boolean playingSkyWars = Utils.getSkyWarsStatus() == 2;
        boolean showClosestEnemyExit = !lastClosestEnemyEntries.isEmpty();
        boolean showClosestEnemy = playingSkyWars && closestEnemy.getInput() != -1;
        if (!showClosestEnemy && !showClosestEnemyExit) {
            return;
        }

        ScaledResolution resolution = new ScaledResolution(mc);
        syncClosestEnemyPosition(resolution);

        if (showClosestEnemy) {
            List<EntityPlayer> enemies = findClosestEnemies((int) closestEnemy.getInput());
            List<EnemyHudEntry> entries = new ArrayList<>();

            for (EntityPlayer enemy : enemies) {
                double distance = Math.round(mc.thePlayer.getDistanceToEntity(enemy));
                String name = enemy.getDisplayName().getFormattedText() + "§r";
                String distanceText = Utils.formatColor(Utils.getDistanceColor(distance))
                        + Utils.asWholeNum(distance) + "m§r";

                entries.add(new EnemyHudEntry(enemy.getEntityId(), name, distanceText, enemy.getHeldItem()));
            }

            drawEnemyHudBox(entries, closestEnemyPosX, closestEnemyPosY);
        }
        else if (showClosestEnemyExit) {
            drawEnemyHudBox(Collections.emptyList(), closestEnemyPosX, closestEnemyPosY);
        }
    }

    private void clear() {
        strengthPlayers.clear();
        spawnedMobs.clear();
        entitySpawnQueue.clear();
        timeWarpPositions.clear();
        thrownPearl = false;
        resetClosestEnemyHud();
    }

    private List<EntityPlayer> findClosestEnemies(int players) {
        Map<UUID, EntityPlayer> enemiesById = new LinkedHashMap<>();

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == null
                    || player == mc.thePlayer
                    || !player.isEntityAlive()
                    || player.isSpectator()
                    || AntiBot.isBot(player)
                    || Utils.isTeammate(player)
                    || Utils.isFriended(player)) {
                continue;
            }

            UUID playerId = player.getUniqueID();
            EntityPlayer existing = enemiesById.get(playerId);
            if (existing == null
                    || player.ticksExisted < existing.ticksExisted
                    || player.ticksExisted == existing.ticksExisted && player.getEntityId() > existing.getEntityId()) {
                enemiesById.put(playerId, player);
            }
        }

        List<EntityPlayer> enemies = new ArrayList<>(enemiesById.values());
        enemies.sort(Comparator.comparingDouble(mc.thePlayer::getDistanceToEntity));

        int amount = Math.min(Math.max(players, 0), enemies.size());
        return new ArrayList<>(enemies.subList(0, amount));
    }

    private void resetClosestEnemyHud() {
        closestEnemySizeInitialized = false;
        closestEnemyVisibilityInitialized = false;
        closestEnemySelection = null;
        lastClosestEnemyEntries = Collections.emptyList();
    }

    public float getClosestEnemyPosX() {
        syncClosestEnemyPosition();
        return closestEnemyPosX;
    }

    public float getClosestEnemyPosY() {
        syncClosestEnemyPosition();
        return closestEnemyPosY;
    }

    public float getClosestEnemyRelativePosX() {
        syncClosestEnemyPosition();
        return closestEnemyRelativePosX;
    }

    public float getClosestEnemyRelativePosY() {
        syncClosestEnemyPosition();
        return closestEnemyRelativePosY;
    }

    public void setClosestEnemyRelativePosition(float normalizedX, float normalizedY) {
        closestEnemyRelativePosX = normalizedX;
        closestEnemyRelativePosY = normalizedY;
        syncClosestEnemyPosition();
    }

    public void setClosestEnemyAbsolutePosition(float absoluteX, float absoluteY) {
        setClosestEnemyAbsolutePosition(absoluteX, absoluteY, new ScaledResolution(mc));
    }

    public void resetClosestEnemyPosition() {
        resetClosestEnemyPosition(new ScaledResolution(mc));
    }

    private void syncClosestEnemyPosition() {
        syncClosestEnemyPosition(new ScaledResolution(mc));
    }

    private void syncClosestEnemyPosition(ScaledResolution resolution) {
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(closestEnemyRelativePosX) || Float.isNaN(closestEnemyRelativePosY)) {
            if (Float.isNaN(closestEnemyPosX) || Float.isNaN(closestEnemyPosY)) {
                closestEnemyPosX = 0.0f;
                closestEnemyPosY = scaledHeight / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f;
            }
            closestEnemyRelativePosX = closestEnemyPosX / scaledWidth;
            closestEnemyRelativePosY = closestEnemyPosY / scaledHeight;
        }
        closestEnemyPosX = closestEnemyRelativePosX * scaledWidth;
        closestEnemyPosY = closestEnemyRelativePosY * scaledHeight;
    }

    private void setClosestEnemyAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        closestEnemyPosX = absoluteX;
        closestEnemyPosY = absoluteY;
        closestEnemyRelativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        closestEnemyRelativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
    }

    private void resetClosestEnemyPosition(ScaledResolution resolution) {
        setClosestEnemyAbsolutePosition(
                0.0f,
                resolution.getScaledHeight() / 4.0f - mc.fontRendererObj.FONT_HEIGHT / 2.0f,
                resolution
        );
    }

    private void renderHeldItem(ItemStack heldItem, float x, float y, float opacity) {
        float scale = 11.0f / 16.0f;
        boolean fading = opacity < 1.0f;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0.0f);
            GL11.glScalef(scale, scale, 1.0f);
            if (fading) {
                setItemOpacity(opacity);
            }
            RenderUtils.renderItemAndEffectIntoGui3D(heldItem, 0, 0);
        }
        finally {
            if (fading) {
                resetItemOpacity();
            }
            GL11.glPopMatrix();
        }
    }

    private void setItemOpacity(float opacity) {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        ITEM_ALPHA_COLOR.clear();
        ITEM_ALPHA_COLOR.put(1.0f).put(1.0f).put(1.0f).put(opacity).flip();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, OpenGlHelper.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_RGB, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_RGB, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_RGB, OpenGlHelper.GL_PRIMARY_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE0_ALPHA, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_SOURCE1_ALPHA, OpenGlHelper.GL_CONSTANT);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, OpenGlHelper.GL_OPERAND1_ALPHA, GL11.GL_SRC_ALPHA);
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ITEM_ALPHA_COLOR);
    }

    private void resetItemOpacity() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        ITEM_ALPHA_COLOR.clear();
        ITEM_ALPHA_COLOR.put(1.0f).put(1.0f).put(1.0f).put(1.0f).flip();
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, ITEM_ALPHA_COLOR);
    }

    private HudBoxBounds drawEnemyHudBox(List<EnemyHudEntry> entries, float x, float textY) {
        float verticalPadding = 4.0f;
        float left = x - 10.0f;
        float top = textY - verticalPadding;
        float contentProgress = updateClosestEnemySelection(entries);
        float visibility = updateClosestEnemyVisibility(entries);
        boolean fadingOut = entries.isEmpty();
        List<EnemyHudEntry> renderedEntries = fadingOut ? lastClosestEnemyEntries : entries;
        if (renderedEntries.isEmpty() || visibility <= 0.0f) {
            return null;
        }

        float horizontalPadding = 3.0f;
        float columnSpacing = 6.0f;
        float itemSpacing = 3.0f;
        float rowHeight = mc.fontRendererObj.FONT_HEIGHT + 4.0f;
        float textX = x + horizontalPadding;
        float maxNameWidth = 0.0f;
        float maxDistanceWidth = 0.0f;
        boolean hasItem = false;

        for (EnemyHudEntry entry : renderedEntries) {
            maxNameWidth = Math.max(
                    maxNameWidth,
                    mc.fontRendererObj.getStringWidth(entry.name)
            );
            maxDistanceWidth = Math.max(
                    maxDistanceWidth,
                    mc.fontRendererObj.getStringWidth(entry.distance)
            );
            if (entry.heldItem != null) {
                hasItem = true;
            }
        }

        float distanceX = textX + maxNameWidth + columnSpacing;
        float itemX = distanceX + maxDistanceWidth + itemSpacing;
        float contentOffset = (1.0f - contentProgress) * 6.0f;
        float contentOpacity = contentProgress * visibility;
        int contentColor = Utils.mergeAlpha(Color.WHITE.getRGB(), Math.max(4, Math.round(255.0f * contentOpacity)));
        float targetRight = distanceX + maxDistanceWidth + horizontalPadding + 1.0f;

        if (hasItem) {
            targetRight = itemX + 14.5f;
        }

        float targetBottom = textY
                + (renderedEntries.size() - 1) * rowHeight
                + mc.fontRendererObj.FONT_HEIGHT
                + verticalPadding
                - 1.0f;

        if (!fadingOut) {
            updateClosestEnemySize(targetRight - left, targetBottom - top);
        }
        float right = left + closestEnemyWidthAnimation.getValue();
        float bottom = top + closestEnemyHeightAnimation.getValue();
        int backgroundColor = Utils.mergeAlpha(Color.BLACK.getRGB(), Math.round(120.0f * visibility));

        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        try {
            enableClosestEnemyAlphaBlending();
            RenderUtils.drawRoundedRectangle(
                    left,
                    top,
                    right,
                    bottom,
                    7.0f,
                    backgroundColor
            );

            for (int i = 0; i < renderedEntries.size(); i++) {
                EnemyHudEntry entry = renderedEntries.get(i);
                float rowY = textY + i * rowHeight;

                enableClosestEnemyAlphaBlending();
                mc.fontRendererObj.drawString(
                        entry.name,
                        textX + contentOffset,
                        rowY,
                        contentColor,
                        true
                );

                float distanceWidth = mc.fontRendererObj.getStringWidth(entry.distance);
                mc.fontRendererObj.drawString(
                        entry.distance,
                        distanceX + maxDistanceWidth - distanceWidth + contentOffset,
                        rowY,
                        contentColor,
                        true
                );

                if (entry.heldItem != null) {
                    renderHeldItem(entry.heldItem, itemX + contentOffset, rowY - 2.0f, contentOpacity);
                }
            }
        }
        finally {
            GL11.glPopAttrib();
            GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
            GlStateManager.enableBlend();
            GlStateManager.disableBlend();
        }

        return new HudBoxBounds(left, top, right, bottom);
    }

    private void enableClosestEnemyAlphaBlending() {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ZERO
        );
    }

    private float updateClosestEnemyVisibility(List<EnemyHudEntry> entries) {
        if (!entries.isEmpty()) {
            lastClosestEnemyEntries = new ArrayList<>(entries);
            if (!closestEnemyVisibilityInitialized) {
                closestEnemyVisibilityAnimation.reset(1.0f);
                closestEnemyVisibilityInitialized = true;
            }
            else if (Float.compare(closestEnemyVisibilityAnimation.getTarget(), 1.0f) != 0) {
                closestEnemyVisibilityAnimation.setTarget(1.0f);
            }
        }
        else {
            if (lastClosestEnemyEntries.isEmpty()) {
                return 0.0f;
            }
            if (!closestEnemyVisibilityInitialized) {
                closestEnemyVisibilityAnimation.reset(1.0f);
                closestEnemyVisibilityInitialized = true;
            }
            if (Float.compare(closestEnemyVisibilityAnimation.getTarget(), 0.0f) != 0) {
                if (closestEnemySizeInitialized) {
                    closestEnemyWidthAnimation.reset(closestEnemyWidthAnimation.getValue());
                    closestEnemyHeightAnimation.reset(closestEnemyHeightAnimation.getValue());
                }
                closestEnemyVisibilityAnimation.setTarget(0.0f);
            }
        }

        float visibility = closestEnemyVisibilityAnimation.getValue();
        if (entries.isEmpty() && visibility <= 0.0f) {
            lastClosestEnemyEntries = Collections.emptyList();
        }
        return visibility;
    }

    private float updateClosestEnemySelection(List<EnemyHudEntry> entries) {
        List<Integer> selection = new ArrayList<>(entries.size());
        for (EnemyHudEntry entry : entries) {
            selection.add(entry.entityId);
        }

        if (closestEnemySelection == null) {
            closestEnemySelection = selection;
            closestEnemyContentAnimation.reset(1.0f);
        }
        else if (!closestEnemySelection.equals(selection)) {
            closestEnemySelection = selection;
            if (selection.isEmpty()) {
                closestEnemyContentAnimation.reset(1.0f);
            }
            else {
                closestEnemyContentAnimation.reset(0.0f);
                closestEnemyContentAnimation.setTarget(1.0f);
            }
        }

        return closestEnemyContentAnimation.getValue();
    }

    private void updateClosestEnemySize(float width, float height) {
        if (!closestEnemySizeInitialized) {
            closestEnemyWidthAnimation.reset(width);
            closestEnemyHeightAnimation.reset(height);
            closestEnemySizeInitialized = true;
            return;
        }

        if (Float.compare(closestEnemyWidthAnimation.getTarget(), width) != 0) {
            closestEnemyWidthAnimation.setTarget(width);
        }

        if (Float.compare(closestEnemyHeightAnimation.getTarget(), height) != 0) {
            closestEnemyHeightAnimation.setTarget(height);
        }
    }

    private static final class SmoothFloatAnimation {
        private final long durationMs;
        private float from;
        private float target;
        private long startedAt;

        private SmoothFloatAnimation(long durationMs) {
            this.durationMs = durationMs;
        }

        private void reset(float value) {
            from = value;
            target = value;
            startedAt = 0L;
        }

        private void setTarget(float value) {
            from = getValue();
            target = value;
            startedAt = System.currentTimeMillis();
        }

        private float getValue() {
            if (startedAt == 0L) {
                return target;
            }

            float progress = (float) (System.currentTimeMillis() - startedAt) / durationMs;
            if (progress >= 1.0f) {
                reset(target);
                return target;
            }

            progress = Math.max(0.0f, progress);
            float eased = progress * progress * (3.0f - 2.0f * progress);
            return from + (target - from) * eased;
        }

        private float getTarget() {
            return target;
        }
    }

    private static final class EnemyHudEntry {
        private final int entityId;
        private final String name;
        private final String distance;
        private final ItemStack heldItem;

        private EnemyHudEntry(int entityId, String name, String distance, ItemStack heldItem) {
            this.entityId = entityId;
            this.name = name;
            this.distance = distance;
            this.heldItem = heldItem;
        }
    }

    private static final class HudBoxBounds {
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        private HudBoxBounds(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private class EditPositionScreen extends GuiScreen {
        private GuiButtonExt resetPosition;
        private HudBoxBounds bounds;
        private boolean dragging;
        private float actualX;
        private float actualY;
        private float dragStartX;
        private float dragStartY;
        private int lastMouseX;
        private int lastMouseY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 95, this.height - 25, 90, 20, "Reset position"));
            syncClosestEnemyPosition(new ScaledResolution(this.mc));
            syncEditorPosition();
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            ScaledResolution resolution = new ScaledResolution(this.mc);
            if (!this.dragging) {
                syncClosestEnemyPosition(resolution);
                syncEditorPosition();
            }

            drawRect(0, 0, this.width, this.height, 0xB2000000);
            setClosestEnemyAbsolutePosition(this.actualX, this.actualY, resolution);
            List<EnemyHudEntry> previewEntries = new ArrayList<>();
            int previewPlayers = Math.max(1, (int) closestEnemy.getInput());

            for (int i = 0; i < previewPlayers; i++) {
                String name = mc.thePlayer.getDisplayName().getFormattedText() + "§r";
                String distance = "§a" + (16 + i * 3) + "m§r";
                previewEntries.add(
                        new EnemyHudEntry(mc.thePlayer.getEntityId(), name, distance, mc.thePlayer.getHeldItem())
                );
            }

            this.bounds = drawEnemyHudBox(previewEntries, this.actualX, this.actualY);

            String message = "Drag the box to reposition it.";
            int messageX = resolution.getScaledWidth() / 2 - this.fontRendererObj.getStringWidth(message) / 2;
            int messageY = resolution.getScaledHeight() / 2 - 10;
            RenderUtils.drawColoredString(message, '-', messageX, messageY, 2L, 0L, true, this.fontRendererObj);

            try {
                this.handleInput();
            }
            catch (IOException ignored) {
            }
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button != 0) {
                return;
            }

            if (this.dragging) {
                this.actualX = this.dragStartX + mouseX - this.lastMouseX;
                this.actualY = this.dragStartY + mouseY - this.lastMouseY;
            }
            else if (this.bounds != null && this.bounds.contains(mouseX, mouseY)) {
                this.dragging = true;
                this.dragStartX = this.actualX;
                this.dragStartY = this.actualY;
                this.lastMouseX = mouseX;
                this.lastMouseY = mouseY;
            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                this.dragging = false;
            }
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == this.resetPosition) {
                this.dragging = false;
                resetClosestEnemyPosition(new ScaledResolution(this.mc));
                syncEditorPosition();
            }
        }

        private void syncEditorPosition() {
            this.actualX = closestEnemyPosX;
            this.actualY = closestEnemyPosY;
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }
    }

    public static boolean onlyAuraHostiles() {
        return ModuleManager.skyWars != null && ModuleManager.skyWars.isEnabled() && ModuleManager.skyWars.onlyAuraHostileMobs.isToggled() && Utils.getSkyWarsStatus() == 2;
    }

    public int getCustomMode() {
        List<String> sidebar = Utils.getSidebarLines();
        if (sidebar.isEmpty()) {
            return -1;
        }
        for (String line : sidebar) {
            line = Utils.stripColor(line);
            if (line.startsWith("Teams left: ")) {
                return 1;
            }
            else if (line.startsWith("Lab: ") || line.startsWith("Mode: Mini")) {
                return 2;
            }
        }
        return -1;
    }

    public static class SpawnEggInfo {
        public Vec3 spawnPos;
        public int tickSpawned;

        public SpawnEggInfo(BlockPos spawnPos, int tickSpawned) {
            this.spawnPos = new Vec3(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            this.tickSpawned = tickSpawned;
        }
    }
}
