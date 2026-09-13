package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.event.PreSlotScrollEvent;
import keystrokesmod.event.SlotUpdateEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityRenderer;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.impl.render.BlockOverlay;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class BedAura extends Module {

    private static final String[] MODES = {"Assist", "Auto"};

    private final SliderSetting mode;
    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting rate;
    private final SliderSetting speed;
    private final SliderSetting breakDelay;
    private final SliderSetting breakSpeed;
    private final ButtonSetting silentAim;
    private final ButtonSetting breakBed;
    private final ButtonSetting onlyOnDamage;
    private final ButtonSetting breakNearBlock;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting swapToTools;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting renderOutline;
    private final ColorSetting outlineColor;

    private static final int MS_PER_TICK = 50;
    private static final int LEGIT_DAMAGE_WINDOW_TICKS = 20;
    private static final double LEGIT_RAY_INSET = 0.05;
    private static final double LEGIT_MIN_FACE_ALIGNMENT = 0.08;
    private static final double BED_FIND_EXTRA_BLOCKS = 1.0;
    private final List<BlockPos[]> bedPairsCache = new ArrayList<>();
    private int scanCooldown;
    private int activeMode = -1;
    private int legitDamageTicksRemaining;
    private int legitDamageCountdownTick = Integer.MIN_VALUE;
    private int previousHurtTime;
    private boolean legitDamageAssistActive;

    private BlockPos targetPos;
    private Vec3 targetHitVec;
    private EnumFacing targetSide;
    private BlockPos legitBedFoot;

    private boolean miningActive;
    private boolean controlsInput;
    private int hotbarProgrammaticDepth;
    private boolean hasSwapped;
    private int previousSlot = -1;

    public BedAura() {
        super("BedAura", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 1, MODES));
        this.registerSetting(breakSpeed = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02));
        this.registerSetting(breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 2.0, 6.0, 0.1));
        this.registerSetting(rate = new SliderSetting("Rate", "ms", 250.0, 50.0, 2000.0, 50.0));
        this.registerSetting(speed = new SliderSetting("Aim speed", 10, 1, 30, 1));
        this.registerSetting(silentAim = new ButtonSetting("Silent aim", false));
        this.registerSetting(onlyOnDamage = new ButtonSetting("Activate on damage", false));
        this.registerSetting(breakBed = new ButtonSetting("Break bed", false));
        this.registerSetting(breakNearBlock = new ButtonSetting("Break near block", true));
        this.registerSetting(prioritizeKillAura = new ButtonSetting("Prioritize KillAura", false));
        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(swapToTools = new ButtonSetting(swapGroup, "Swap to tools", true));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));
        this.registerSetting(renderOutline = new ButtonSetting("Render block outline", true));
        this.registerSetting(outlineColor = new ColorSetting("Outline color", 255, 64, 64, 229));
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        boolean autoMode = mode.getInput() == 1;
        speed.setVisible(!autoMode, this);
        silentAim.setVisible(!autoMode, this);
        breakBed.setVisible(!autoMode, this);
        onlyOnDamage.setVisible(!autoMode, this);
        breakNearBlock.setVisible(autoMode, this);
        swapGroup.setVisible(autoMode, this);
        swapToTools.setVisible(autoMode, this);
        switchBackWhenDone.setVisible(autoMode && swapToTools.isToggled(), this);
        overrideSwapBack.setVisible(autoMode && swapToTools.isToggled(), this);
        outlineColor.setVisible(renderOutline.isToggled(), this);
    }

    @Override
    public void onDisable() {
        resetMining();
        bedPairsCache.clear();
        scanCooldown = 0;
        activeMode = -1;
        resetLegitDamageCountdown();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        applyMiningKeyState();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMouse(MouseEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (e.button == 0 || e.button == 1) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreAttack(PreAttackEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotScroll(PreSlotScrollEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (hasSwapped && overrideSwapBack.isToggled() && Utils.nullCheck()) {
            int slot = Integer.compare(e.slot, 0);
            previousSlot = Math.floorMod(mc.thePlayer.inventory.currentItem - slot, InventoryPlayer.getHotbarSize());
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotUpdate(SlotUpdateEvent e) {
        if (!shouldSuppressManualMouse() || hotbarProgrammaticDepth > 0) {
            return;
        }
        if (hasSwapped && overrideSwapBack.isToggled()) {
            previousSlot = e.slot;
        }
        e.setCanceled(true);
    }

    private boolean shouldSuppressManualMouse() {
        return isAutoMode() && miningActive && isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura();
    }

    public void applyMiningKeyState() {
        if (!canMineBlocks() || shouldYieldToKillAura()) {
            if (miningActive) {
                resetMining();
            }
            return;
        }
        boolean shouldBreak = isAutoMode() || breakBed.isToggled() && isLookingAtTarget();
        if (!miningActive || !shouldBreak || !isEnabled() || !Utils.nullCheck() || mc.currentScreen != null) {
            restoreInput();
            return;
        }
        int atk = mc.gameSettings.keyBindAttack.getKeyCode();
        controlsInput = true;
        if (isAutoMode()) {
            KeyBinding.setKeyBindState(atk, false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        }
        KeyBinding.setKeyBindState(atk, true);
    }

    public BlockPos getAuraTargetPos() {
        return miningActive && canMineBlocks() ? targetPos : null;
    }

    public boolean isActivelyMining() {
        return miningActive && (isAutoMode() || breakBed.isToggled() && isLookingAtTarget())
                && isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura();
    }

    public boolean isPrioritizingKillAura() {
        return prioritizeKillAura.isToggled();
    }

    public boolean shouldOverrideFastMine() {
        return isActivelyMining();
    }

    public float getBreakSpeedMultiplier() {
        float multiplier = (float) breakSpeed.getInput();
        return multiplier > 1.0f ? multiplier : 1.0f;
    }

    public int getBreakDelayTicks() {
        return Math.max(0, Math.min(5, (int) (breakDelay.getInput() / 50.0)));
    }

    public float getAuraBreakProgress() {
        if (!canMineBlocks() || !miningActive || mc.playerController == null) {
            return 0f;
        }
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP) mc.playerController;
        BlockPos currentBlock = pc.getCurrentBlock();
        if (targetPos == null || currentBlock == null || !targetPos.equals(currentBlock)) {
            return 0f;
        }
        return pc.getCurBlockDamageMP();
    }

    public boolean shouldOverrideMouseOver() {
        return isEnabled() && miningActive && Utils.nullCheck() && canMineBlocks()
                && targetPos != null && targetHitVec != null && targetSide != null && !shouldYieldToKillAura();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }
        if (!isAutoMode()) {
            return;
        }
        if (mc.getRenderViewEntity() == null) {
            return;
        }

        MovingObjectPosition mop = new MovingObjectPosition(targetHitVec, targetSide, targetPos);
        mc.objectMouseOver = mop;
        mc.pointedEntity = null;

        EntityRenderer renderer = mc.entityRenderer;
        if (renderer instanceof IAccessorEntityRenderer) {
            ((IAccessorEntityRenderer) renderer).setPointedEntity(null);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        int selectedMode = (int) mode.getInput();
        if (activeMode != selectedMode) {
            resetMining();
            bedPairsCache.clear();
            scanCooldown = 0;
            activeMode = selectedMode;
            resetLegitDamageCountdown();
        }
        if (!isEnabled() || !Utils.nullCheck() || mc.currentScreen != null || !canMineBlocks()) {
            resetMining();
            return;
        }
        if (shouldYieldToKillAura()) {
            resetMining();
            return;
        }
        if (e.scriptRotations) {
            resetMining();
            return;
        }
        if (!isAutoMode() && !legitConditionsMet()) {
            resetMining();
            return;
        }

        double reach = range.getInput();
        double reachSq = reach * reach;

        if (--scanCooldown <= 0) {
            scanCooldown = Math.max(1, (int) Math.round(rate.getInput() / (double) MS_PER_TICK));
            rebuildBedPairsCache(reach + BED_FIND_EXTRA_BLOCKS);
        }

        if (bedPairsCache.isEmpty()) {
            if (!isAutoMode() && legitDamageAssistActive) {
                resetLegitDamageCountdown();
            }
            resetMining();
            return;
        }

        Choice best = isAutoMode() ? chooseBestTarget(reachSq) : chooseBestLegitTarget(reachSq);
        if (best == null) {
            if (!isAutoMode() && legitDamageAssistActive) {
                resetLegitDamageCountdown();
            }
            resetMining();
            return;
        }

        targetPos = best.pos;
        targetHitVec = best.hitVec;
        targetSide = best.side;
        miningActive = true;
        if (!isAutoMode() && onlyOnDamage.isToggled()) {
            legitDamageAssistActive = true;
        }

        if (isAutoMode()) {
            equipBestHotbarTool(BlockUtils.getBlock(targetPos));
        }

        if (!isAutoMode() && !silentAim.isToggled()) {
            return;
        }

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float[] targetRotations = RotationUtils.getRotationsToPoint(
                targetHitVec.xCoord, targetHitVec.yCoord, targetHitVec.zCoord,
                baseYaw, basePitch
        );
        float[] r = isAutoMode()
                ? targetRotations
                : RotationUtils.smoothRotation(baseYaw, basePitch, targetRotations[0], targetRotations[1], (int) speed.getInput());
        e.setYaw(r[0]);
        e.setPitch(r[1]);
    }

    @Override
    public void onUpdate() {
        if (!isEnabled() || isAutoMode() || silentAim.isToggled() || !miningActive || !Utils.nullCheck()
                || mc.currentScreen != null || !canMineBlocks() || shouldYieldToKillAura() || !legitConditionsMet()) {
            return;
        }

        float baseYaw = mc.thePlayer.rotationYaw;
        float basePitch = mc.thePlayer.rotationPitch;
        float[] targetRotations = RotationUtils.getRotationsToPoint(
                targetHitVec.xCoord, targetHitVec.yCoord, targetHitVec.zCoord,
                baseYaw, basePitch
        );
        float[] r = RotationUtils.smoothRotation(
                baseYaw, basePitch, targetRotations[0], targetRotations[1], (int) speed.getInput()
        );
        mc.thePlayer.rotationYaw = r[0];
        mc.thePlayer.rotationPitch = r[1];
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!isEnabled() || !renderOutline.isToggled() || !miningActive || targetPos == null || !Utils.nullCheck() || !canMineBlocks()) {
            return;
        }
        IBlockState st = mc.theWorld.getBlockState(targetPos);
        Block b = st.getBlock();
        if (b == null || b == Blocks.air) {
            return;
        }
        int c = outlineColor.getColor();
        BlockOverlay.renderBlockOutline(targetPos, c, c, 2.0f, true);
    }

    private void resetMining() {
        miningActive = false;
        if (swapToTools.isToggled() && switchBackWhenDone.isToggled() && previousSlot != -1 && Utils.nullCheck()) {
            setSlot(previousSlot);
        }
        restoreInput();
        hotbarProgrammaticDepth = 0;
        targetPos = null;
        targetHitVec = null;
        targetSide = null;
        legitBedFoot = null;
        hasSwapped = false;
        previousSlot = -1;
    }

    private void restoreInput() {
        if (controlsInput) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), Mouse.isButtonDown(0));
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), Mouse.isButtonDown(1));
            controlsInput = false;
        }
    }

    private void rebuildBedPairsCache(double searchRange) {
        bedPairsCache.clear();
        Set<BlockPos> seenFeet = new HashSet<>();
        int ri = (int) Math.ceil(searchRange);
        BlockPos origin = new BlockPos(mc.thePlayer);

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockPos[] pair = footHeadPair(p);
                    if (pair == null) {
                        continue;
                    }
                    BlockPos foot = pair[0];
                    if (seenFeet.contains(foot)) {
                        continue;
                    }
                    if (!bedInSearchRange(pair, searchRange)) {
                        continue;
                    }
                    Vec3 center = bedCenter(pair);
                    if (!inFov(center, (float) fov.getInput())) {
                        continue;
                    }
                    seenFeet.add(foot);
                    bedPairsCache.add(pair);
                }
            }
        }

        if (ModuleManager.bedwars != null) {
            ModuleManager.bedwars.removeOwnBedPair(bedPairsCache);
        }
    }

    private BlockPos[] footHeadPair(BlockPos at) {
        IBlockState st = mc.theWorld.getBlockState(at);
        if (!(st.getBlock() instanceof BlockBed)) {
            return null;
        }
        BlockBed.EnumPartType part = (BlockBed.EnumPartType) st.getValue(BlockBed.PART);
        EnumFacing facing = (EnumFacing) st.getValue(BlockBed.FACING);
        BlockPos foot = part == BlockBed.EnumPartType.FOOT ? at : at.offset(facing.getOpposite());
        IBlockState footSt = mc.theWorld.getBlockState(foot);
        if (!(footSt.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (footSt.getValue(BlockBed.PART) != BlockBed.EnumPartType.FOOT) {
            return null;
        }
        EnumFacing footFacing = (EnumFacing) footSt.getValue(BlockBed.FACING);
        BlockPos head = foot.offset(footFacing);
        IBlockState hs = mc.theWorld.getBlockState(head);
        if (!(hs.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (hs.getValue(BlockBed.PART) != BlockBed.EnumPartType.HEAD) {
            return null;
        }
        if (hs.getValue(BlockBed.FACING) != footFacing) {
            return null;
        }
        return new BlockPos[]{foot, head};
    }

    private Vec3 bedCenter(BlockPos[] pair) {
        AxisAlignedBB a = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        return new Vec3((a.minX + a.maxX) * 0.5, (a.minY + a.maxY) * 0.5, (a.minZ + a.maxZ) * 0.5);
    }

    private boolean bedInSearchRange(BlockPos[] pair, double searchRadius) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        double r2 = searchRadius * searchRadius + 1e-4;
        AxisAlignedBB u = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        Vec3 onBox = RotationUtils.closestPointOnAabb(u, eye);
        if (eye.squareDistanceTo(onBox) <= r2) {
            return true;
        }
        Vec3 mid = new Vec3((u.minX + u.maxX) * 0.5, (u.minY + u.maxY) * 0.5, (u.minZ + u.maxZ) * 0.5);
        return eye.squareDistanceTo(mid) <= r2;
    }

    private boolean inFov(Vec3 worldPoint, float fovDeg) {
        if (fovDeg >= 360) {
            return true;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = mc.thePlayer.getLook(1f);
        Vec3 to = worldPoint.subtract(eyes);
        double len = to.lengthVector();
        if (len < 1e-6) {
            return true;
        }
        to = new Vec3(to.xCoord / len, to.yCoord / len, to.zCoord / len);
        double dot = look.xCoord * to.xCoord + look.yCoord * to.yCoord + look.zCoord * to.zCoord;
        double ang = Math.acos(MathHelper.clamp_double(dot, -1.0, 1.0)) * (180.0 / Math.PI);
        return ang <= fovDeg * 0.5;
    }

    private Choice chooseBestLegitTarget(double reachSq) {
        List<BlockPos[]> exposed = new ArrayList<>();
        for (BlockPos[] pair : bedPairsCache) {
            if (isBedExposed(pair)) {
                exposed.add(pair);
            }
        }
        sortBedsByEyeDistance(exposed);

        if (legitBedFoot != null) {
            for (BlockPos[] pair : exposed) {
                if (!legitBedFoot.equals(pair[0])) {
                    continue;
                }
                Choice locked = getLockedLegitChoice(pair, reachSq);
                if (locked != null) {
                    return locked;
                }
                Choice replacement = chooseLegitPoint(pair, reachSq);
                if (replacement != null) {
                    return replacement;
                }
                break;
            }
            legitBedFoot = null;
        }

        for (BlockPos[] pair : exposed) {
            Choice choice = chooseLegitPoint(pair, reachSq);
            if (choice != null) {
                legitBedFoot = pair[0];
                return choice;
            }
        }
        return null;
    }

    private Choice chooseLegitPoint(BlockPos[] pair, double reachSq) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        List<Choice> candidates = new ArrayList<>();
        AxisAlignedBB bedBox = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        addLegitBedCandidates(pair, bedBox, reachSq, candidates);
        if (candidates.isEmpty()) {
            return null;
        }
        return Collections.min(candidates, Comparator.<Choice>comparingDouble(choice -> legitAimScore(choice, eye))
                .thenComparingDouble(choice -> eye.squareDistanceTo(choice.hitVec)));
    }

    private Choice getLockedLegitChoice(BlockPos[] pair, double reachSq) {
        if (targetPos == null || targetHitVec == null || targetSide == null
                || !targetPos.equals(pair[0]) && !targetPos.equals(pair[1])) {
            return null;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        if (eye.squareDistanceTo(targetHitVec) > reachSq + 1e-3) {
            return null;
        }
        Vec3 inside = targetHitVec.addVector(
                -targetSide.getFrontOffsetX() * LEGIT_RAY_INSET,
                -targetSide.getFrontOffsetY() * LEGIT_RAY_INSET,
                -targetSide.getFrontOffsetZ() * LEGIT_RAY_INSET
        );
        MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(eye, inside, false, false, false);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !targetPos.equals(trace.getBlockPos()) || trace.hitVec == null || trace.sideHit == null
                || !isCenteredBedHit(trace.getBlockPos(), trace.hitVec, trace.sideHit)
                || faceAlignment(eye, trace.hitVec, trace.sideHit) < LEGIT_MIN_FACE_ALIGNMENT) {
            return null;
        }
        return new Choice(targetPos, targetHitVec, targetSide);
    }

    private void addLegitBedCandidates(BlockPos[] pair, AxisAlignedBB box, double reachSq, List<Choice> out) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        double[] samples = {0.5, 0.25, 0.75, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8};

        for (EnumFacing face : EnumFacing.values()) {
            if (face == EnumFacing.DOWN) {
                continue;
            }

            for (double first : samples) {
                for (double second : samples) {
                    double x = (box.minX + box.maxX) * 0.5;
                    double y = (box.minY + box.maxY) * 0.5;
                    double z = (box.minZ + box.maxZ) * 0.5;

                    if (face == EnumFacing.UP) {
                        x = interpolate(box.minX, box.maxX, first);
                        y = box.maxY;
                        z = interpolate(box.minZ, box.maxZ, second);
                    } else if (face == EnumFacing.NORTH || face == EnumFacing.SOUTH) {
                        x = interpolate(box.minX, box.maxX, first);
                        y = interpolate(box.minY, box.maxY, second);
                        z = face == EnumFacing.NORTH ? box.minZ : box.maxZ;
                    } else {
                        x = face == EnumFacing.WEST ? box.minX : box.maxX;
                        y = interpolate(box.minY, box.maxY, first);
                        z = interpolate(box.minZ, box.maxZ, second);
                    }

                    addLegitBedCandidate(pair, face, new Vec3(x, y, z), eye, reachSq, out);
                }
            }
        }
    }

    private void addLegitBedCandidate(BlockPos[] pair, EnumFacing face, Vec3 point, Vec3 eye, double reachSq, List<Choice> out) {
        Vec3 inside = point.addVector(
                -face.getFrontOffsetX() * LEGIT_RAY_INSET,
                -face.getFrontOffsetY() * LEGIT_RAY_INSET,
                -face.getFrontOffsetZ() * LEGIT_RAY_INSET
        );
        MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(eye, inside, false, false, false);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || trace.hitVec == null || trace.sideHit == null
                || !pair[0].equals(trace.getBlockPos()) && !pair[1].equals(trace.getBlockPos())) {
            return;
        }
        if (eye.squareDistanceTo(trace.hitVec) > reachSq + 1e-3) {
            return;
        }
        if (!isCenteredBedHit(trace.getBlockPos(), trace.hitVec, trace.sideHit)) {
            return;
        }
        if (faceAlignment(eye, trace.hitVec, trace.sideHit) < LEGIT_MIN_FACE_ALIGNMENT) {
            return;
        }
        out.add(new Choice(trace.getBlockPos(), trace.hitVec, trace.sideHit));
    }

    private boolean isCenteredBedHit(BlockPos pos, Vec3 hit, EnumFacing side) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null) {
            return false;
        }
        double marginX = Math.min(0.15, (box.maxX - box.minX) * 0.25);
        double marginY = Math.min(0.12, (box.maxY - box.minY) * 0.25);
        double marginZ = Math.min(0.15, (box.maxZ - box.minZ) * 0.25);
        boolean centeredX = hit.xCoord >= box.minX + marginX && hit.xCoord <= box.maxX - marginX;
        boolean centeredY = hit.yCoord >= box.minY + marginY && hit.yCoord <= box.maxY - marginY;
        boolean centeredZ = hit.zCoord >= box.minZ + marginZ && hit.zCoord <= box.maxZ - marginZ;
        if (side == EnumFacing.UP || side == EnumFacing.DOWN) {
            return centeredX && centeredZ;
        }
        if (side == EnumFacing.NORTH || side == EnumFacing.SOUTH) {
            return centeredX && centeredY;
        }
        return centeredY && centeredZ;
    }

    private double legitAimScore(Choice choice, Vec3 eye) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(choice.pos);
        if (box == null) {
            return Double.POSITIVE_INFINITY;
        }
        double x = (choice.hitVec.xCoord - (box.minX + box.maxX) * 0.5) / (box.maxX - box.minX);
        double y = (choice.hitVec.yCoord - (box.minY + box.maxY) * 0.5) / (box.maxY - box.minY);
        double z = (choice.hitVec.zCoord - (box.minZ + box.maxZ) * 0.5) / (box.maxZ - box.minZ);
        double alignment = faceAlignment(eye, choice.hitVec, choice.side);
        return x * x + y * y + z * z + (1.0 - alignment) * 0.75;
    }

    private double faceAlignment(Vec3 eye, Vec3 hit, EnumFacing side) {
        double x = eye.xCoord - hit.xCoord;
        double y = eye.yCoord - hit.yCoord;
        double z = eye.zCoord - hit.zCoord;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-6) {
            return 1.0;
        }
        return Math.max(0.0, (x * side.getFrontOffsetX() + y * side.getFrontOffsetY()
                + z * side.getFrontOffsetZ()) / length);
    }

    private double interpolate(double min, double max, double amount) {
        return min + (max - min) * amount;
    }

    private Choice chooseBestTarget(double reachSq) {
        IAccessorPlayerControllerMP pc = (IAccessorPlayerControllerMP) mc.playerController;
        float curProg = pc.getCurBlockDamageMP();
        BlockPos breaking = pc.getCurrentBlock();

        List<BlockPos[]> exposed = new ArrayList<>();
        List<BlockPos[]> covered = new ArrayList<>();
        for (BlockPos[] pair : bedPairsCache) {
            if (isBedExposed(pair)) {
                exposed.add(pair);
            } else {
                covered.add(pair);
            }
        }
        sortBedsByEyeDistance(exposed);
        sortBedsByEyeDistance(covered);

        Choice c = pickBestOnClosestBedWithCandidates(exposed, reachSq, curProg, breaking);
        if (c != null) {
            return c;
        }
        return pickBestOnClosestBedWithCandidates(covered, reachSq, curProg, breaking);
    }

    private void sortBedsByEyeDistance(List<BlockPos[]> pairs) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        pairs.sort(Comparator.comparingDouble(p -> eye.squareDistanceTo(bedCenter(p))));
    }

    private Choice pickBestOnClosestBedWithCandidates(List<BlockPos[]> sortedPairs, double reachSq, float curProg, BlockPos breaking) {
        for (BlockPos[] pair : sortedPairs) {
            List<Choice> candidates = buildCandidates(pair, reachSq);
            if (candidates.isEmpty()) {
                continue;
            }
            Choice best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Choice ch : candidates) {
                double score = scoreChoice(ch, curProg, breaking);
                if (score < bestScore) {
                    bestScore = score;
                    best = ch;
                }
            }
            return best;
        }
        return null;
    }

    private double scoreChoice(Choice ch, float curProg, BlockPos breaking) {
        Block block = BlockUtils.getBlock(ch.pos);
        float bestHotbar = BlockUtils.maxDigRateAcrossSlots(block, InventoryPlayer.getHotbarSize());
        if (bestHotbar <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double timeEst = 1.0 / bestHotbar;

        if (breaking != null && breaking.equals(ch.pos) && curProg > 0.02f) {
            timeEst -= curProg * 12.0;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        timeEst += eye.squareDistanceTo(ch.hitVec) * 0.002;
        return timeEst;
    }

    private List<Choice> buildCandidates(BlockPos[] pair, double reachSq) {
        List<Choice> out = new ArrayList<>();

        if (!breakNearBlock.isToggled()) {
            for (BlockPos bp : pair) {
                addBlockCandidate(bp, reachSq, out);
            }
            return out;
        }

        boolean exposed = isBedExposed(pair);

        if (exposed) {
            for (BlockPos bp : pair) {
                addBlockCandidate(bp, reachSq, out);
            }
        } else {
            Set<BlockPos> seen = new HashSet<>();
            for (BlockPos bp : pair) {
                for (EnumFacing f : EnumFacing.values()) {
                    if (f == EnumFacing.DOWN) {
                        continue;
                    }
                    BlockPos n = bp.offset(f);
                    if (seen.contains(n)) {
                        continue;
                    }
                    IBlockState st = mc.theWorld.getBlockState(n);
                    Block b = st.getBlock();
                    if (canHitThrough(b) || b instanceof BlockBed) {
                        continue;
                    }
                    float hard = b.getBlockHardness(mc.theWorld, n);
                    if (hard < 0) {
                        continue;
                    }
                    seen.add(n);
                    addBlockCandidate(n, reachSq, out);
                }
            }
        }
        return out;
    }

    private boolean isBedExposed(BlockPos[] pair) {
        for (BlockPos bp : pair) {
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos n = bp.offset(f);
                if (canHitThrough(mc.theWorld.getBlockState(n).getBlock())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canHitThrough(Block block) {
        return block == Blocks.air || block == Blocks.iron_bars || block == Blocks.water
                || block == Blocks.flowing_water
                || block == Blocks.lava
                || block == Blocks.flowing_lava
                || block == Blocks.tallgrass
                || block == Blocks.red_flower
                || block == Blocks.yellow_flower
                || block == Blocks.double_plant
                || block == Blocks.deadbush
                || block == Blocks.vine
                || block == Blocks.end_portal_frame
                || (block instanceof BlockFence)
                || (block instanceof BlockWall)
                || (block instanceof BlockFenceGate)
                || (block instanceof BlockPane);
    }

    private void addBlockCandidate(BlockPos pos, double reachSq, List<Choice> out) {
        IBlockState st = mc.theWorld.getBlockState(pos);
        Block block = st.getBlock();
        if (block == Blocks.air) {
            return;
        }
        float hard = block.getBlockHardness(mc.theWorld, pos);
        if (hard < 0) {
            return;
        }
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(pos);
        if (bb == null) {
            return;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        Vec3 hit = RotationUtils.closestPointOnAabb(bb, eye);
        if (eye.squareDistanceTo(hit) > reachSq + 1e-3) {
            return;
        }

        MovingObjectPosition trace = block.collisionRayTrace(mc.theWorld, pos, eye, hit.addVector(
                (hit.xCoord - eye.xCoord) * 0.01,
                (hit.yCoord - eye.yCoord) * 0.01,
                (hit.zCoord - eye.zCoord) * 0.01
        ));
        EnumFacing side = BlockUtils.facingFromBlockCenterToPoint(pos, hit);
        if (trace != null && trace.hitVec != null && trace.sideHit != null && pos.equals(trace.getBlockPos())) {
            hit = trace.hitVec;
            side = trace.sideHit;
        }
        if (block instanceof BlockBed && side == EnumFacing.DOWN) {
            return;
        }

        out.add(new Choice(pos, hit, side));
    }

    private void equipBestHotbarTool(Block block) {
        if (!swapToTools.isToggled()) {
            return;
        }
        int slot = Utils.getTool(block);
        if (slot < 0) {
            return;
        }
        if (previousSlot == -1 && slot != mc.thePlayer.inventory.currentItem) {
            previousSlot = mc.thePlayer.inventory.currentItem;
        }
        if (slot != mc.thePlayer.inventory.currentItem) {
            setSlot(slot);
        }
    }

    private void setSlot(int slot) {
        if (!swapToTools.isToggled() || slot == -1 || slot == mc.thePlayer.inventory.currentItem) {
            return;
        }
        hotbarProgrammaticDepth++;
        try {
            mc.thePlayer.inventory.currentItem = slot;
            hasSwapped = true;
            ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        } finally {
            hotbarProgrammaticDepth--;
        }
    }

    private boolean canMineBlocks() {
        return mc.thePlayer.capabilities.allowEdit
                && !mc.thePlayer.capabilities.isCreativeMode
                && !mc.thePlayer.isSpectator();
    }

    private boolean isAutoMode() {
        return mode.getInput() == 1;
    }

    private boolean legitConditionsMet() {
        if (!onlyOnDamage.isToggled()) {
            resetLegitDamageCountdown();
            return true;
        }
        int currentTick = mc.thePlayer.ticksExisted;
        if (legitDamageCountdownTick != currentTick) {
            int hurtTime = mc.thePlayer.hurtTime;
            if (hurtTime > 0 && (previousHurtTime == 0 || hurtTime > previousHurtTime)) {
                legitDamageTicksRemaining = LEGIT_DAMAGE_WINDOW_TICKS;
            } else if (!legitDamageAssistActive && legitDamageTicksRemaining > 0) {
                legitDamageTicksRemaining--;
            }
            previousHurtTime = hurtTime;
            legitDamageCountdownTick = currentTick;
        }
        return legitDamageAssistActive || legitDamageTicksRemaining > 0;
    }

    private void resetLegitDamageCountdown() {
        legitDamageTicksRemaining = 0;
        legitDamageCountdownTick = Integer.MIN_VALUE;
        previousHurtTime = Utils.nullCheck() ? mc.thePlayer.hurtTime : 0;
        legitDamageAssistActive = false;
    }

    private boolean isLookingAtTarget() {
        MovingObjectPosition mouseOver = mc.objectMouseOver;
        return targetPos != null && mouseOver != null
                && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && targetPos.equals(mouseOver.getBlockPos());
    }

    private boolean shouldYieldToKillAura() {
        if (!prioritizeKillAura.isToggled()) {
            return false;
        }
        return ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && KillAura.target != null;
    }

    private static final class Choice {
        final BlockPos pos;
        final Vec3 hitVec;
        final EnumFacing side;

        Choice(BlockPos pos, Vec3 hitVec, EnumFacing side) {
            this.pos = pos;
            this.hitVec = hitVec;
            this.side = side;
        }
    }
}
