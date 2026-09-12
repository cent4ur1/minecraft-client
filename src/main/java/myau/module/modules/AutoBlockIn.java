package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.LeftClickMouseEvent;
import myau.events.MoveInputEvent;
import myau.events.PlayerUpdateEvent;
import myau.events.Render2DEvent;
import myau.events.RightClickMouseEvent;
import myau.events.SwapItemEvent;
import myau.events.UpdateEvent;
import myau.management.RotationState;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.property.properties.TextProperty;
import myau.util.KeyBindUtil;
import myau.util.MoveUtil;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.BlockWall;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Port of {@code keystrokesmod.module.impl.player.AutoBlockin} (raven-bS-beta)
 * into this client's module framework. Behaviour (target selection, rotation
 * smoothing, placement, progress display, click/scroll suppression) is kept
 * as close to Raven as this client's APIs allow.
 *
 * <p>Class name stays {@code AutoBlockIn} (myau spelling) so existing
 * references ({@code BedNuker}, {@code KillAura}, {@code MixinEntityRenderer},
 * {@code MixinGuiIngame}) keep compiling.</p>
 *
 * <p>Adaptations:</p>
 * <ul>
 *   <li>{@code SliderSetting}/{@code ButtonSetting} mapped to
 *       {@link IntProperty}/{@link BooleanProperty}/{@link ModeProperty}.</li>
 *   <li>{@code KeySetting} (Raven codes: 0 = unbound, 1-999 = LWJGL key,
 *       1000+ = mouse button + 1000) mapped to {@code activation-key}
 *       {@link IntProperty} with the same coding. {@code 0} means the module
 *       stays idle until a key is set, exactly like Raven.</li>
 *   <li>{@code ItemListSetting} mapped to {@code ignored-blocks}
 *       {@link TextProperty}: comma-separated, matched case-insensitively
 *       against the block's registry name, unlocalized name, or display
 *       name.</li>
 *   <li>{@code Show progress} gains an explicit {@code OFF} mode (Raven used a
 *       hidden -1 state for the same purpose).</li>
 *   <li>{@code ClientRotationEvent} emulated with {@link UpdateEvent} PRE
 *       ({@code event.getYaw()/getPitch()} are the server rotations).</li>
 *   <li>{@code PreUpdateEvent} (pre-motion flush) emulated with
 *       {@link PlayerUpdateEvent}, which fires right before
 *       {@code onUpdateWalkingPlayer}.</li>
 *   <li>{@code RotationHelper.forceMovementFix} emulated with
 *       {@code setPervRotation(yaw, 6)} + strafe correction in
 *       {@link #onMoveInput} (same pattern the previous implementation
 *       used).</li>
 *   <li>{@code TickEvent.RenderTickEvent} emulated with
 *       {@link Render2DEvent}.</li>
 *   <li>Forge {@code MouseEvent} cancel mapped to {@link LeftClickMouseEvent} /
 *       {@link RightClickMouseEvent} cancels; {@code PreSlotScrollEvent} mapped
 *       to {@link SwapItemEvent} cancel.</li>
 *   <li>{@code BedAura.shouldOverrideMouseOver()} mapped to
 *       {@code BedNuker.isEnabled() && BedNuker.isReady()} (same pattern
 *       {@code LagRange} uses).</li>
 *   <li>{@code RotationUtils}/{@code BlockUtils}/{@code RenderUtils} helpers
 *       used here are ported verbatim as private methods below.</li>
 *   <li>{@code item-spoof} + {@link #getSlot()} are kept for
 *       {@code MixinEntityRenderer}/{@code MixinGuiIngame} compatibility.</li>
 * </ul>
 */
public class AutoBlockIn extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.NORTH
    };

    private static final SupportOffset[] SUPPORTS = {
            new SupportOffset(0, 1, 0, EnumFacing.DOWN),
            new SupportOffset(0, -1, 0, EnumFacing.UP),
            new SupportOffset(0, 0, -1, EnumFacing.SOUTH),
            new SupportOffset(0, 0, 1, EnumFacing.NORTH),
            new SupportOffset(1, 0, 0, EnumFacing.WEST),
            new SupportOffset(-1, 0, 0, EnumFacing.EAST)
    };

    private static final double REACH = 4.5;
    private static final double GRID_INSET = 0.05;
    private static final double GRID_STEP = 0.2;
    private static final int GRID_N = (int) Math.round(1.0 / GRID_STEP);
    private static final float FAR_THRESHOLD = 180.0F;
    private static final int ROTATION_PRIORITY = 6;

    public final IntProperty speed = new IntProperty("speed", 10, 1, 30);
    public final IntProperty randomization = new IntProperty("randomization", 10, 0, 100);
    public final IntProperty rotationTolerance = new IntProperty("rotation-tolerance", 25, 20, 100);
    public final ModeProperty showProgress = new ModeProperty("show-progress", 0, new String[]{"CIRCLE", "PERCENTAGE", "OFF"});
    public final BooleanProperty disableInCreative = new BooleanProperty("disable-in-creative", true);
    public final BooleanProperty skipNearBed = new BooleanProperty("skip-near-bed", true);
    public final IntProperty activationKey = new IntProperty("activation-key", 0, 0, 1100);
    public final BooleanProperty ignoreBlocks = new BooleanProperty("ignore-blocks", false);
    public final TextProperty ignoredBlocks = new TextProperty("ignored-blocks", "", this.ignoreBlocks::getValue);
    public final BooleanProperty disableHotbarScrolling = new BooleanProperty("disable-hotbar-scrolling", false);
    public final BooleanProperty itemSpoof = new BooleanProperty("item-spoof", true);

    private boolean placing;
    private boolean slotWasSwapped;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private boolean placeQueued;

    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private float aimYaw;
    private float aimPitch;

    private BlockPos hitAt;
    private EnumFacing hitSide;
    private Vec3 placeAt;

    private float fillCount;
    private float lastFillCount = -1;
    private float circleProgress;
    private float animStartProgress;
    private float animTargetProgress;
    private long animStartTime;

    private long progressFadeInStart = -1;
    private long progressFadeOutStart = -1;
    private float previousProgressAlpha;

    private boolean lastTargetAdjacent;
    private float fillTargetCount;
    private float lastFillTargetCount = -1;

    public AutoBlockIn() {
        super("AutoBlockIn", false);
    }

    @Override
    public void onEnabled() {
        disablePlacing();
        placeQueued = false;
        resetProgressState();
    }

    @Override
    public void onDisabled() {
        disablePlacing();
        placeQueued = false;
        resetProgressState();
    }

    private void resetProgressState() {
        fillCount = 0;
        fillTargetCount = 0;
        lastFillCount = -1;
        lastFillTargetCount = -1;
        circleProgress = 0;
        animStartProgress = 0;
        animTargetProgress = 0;
        resetProgressFade();
    }

    /**
     * Spoof slot for MixinEntityRenderer/MixinGuiIngame. The port swaps the
     * held slot for real (like Raven), so this matches the equipped slot
     * while placing and stays -1 otherwise.
     */
    public int getSlot() {
        return this.placing ? this.plannedSlot : -1;
    }

    private boolean isActivationPressed() {
        int key = this.activationKey.getValue();
        if (key == 0) {
            return false;
        }
        if (key >= 1000) {
            return Mouse.isButtonDown(key - 1000);
        }
        return KeyBindUtil.isKeyDown(key);
    }

    private boolean isBedNukerOverriding() {
        try {
            BedNuker bedNuker = (BedNuker) Myau.moduleManager.modules.get(BedNuker.class);
            return bedNuker != null && bedNuker.isEnabled() && bedNuker.isReady();
        } catch (Exception e) {
            return false;
        }
    }

    @EventTarget(Priority.HIGH)
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.disableInCreative.getValue() && mc.thePlayer.capabilities.isCreativeMode) {
            return;
        }
        if (isBedNukerOverriding()) {
            return;
        }

        float baseYaw = event.getYaw();
        float basePitch = event.getPitch();

        runTargetSelection();

        if (mc.currentScreen != null) {
            disablePlacing();
        }
        if (!this.placing || this.targetHitPos == null) {
            return;
        }

        float[] sm = smoothRotation(baseYaw, basePitch, this.aimYaw, this.aimPitch,
                this.speed.getValue(), (float) this.randomization.getValue());
        MovingObjectPosition mop = rayCastBlock(REACH, baseYaw, basePitch);

        if (mop != null) {
            BlockPos hitBlock = mop.getBlockPos();
            EnumFacing side = mop.sideHit;
            if (hitBlock.equals(this.targetHitPos) && side == this.targetSide) {
                double tol = this.rotationTolerance.getValue();
                if (Math.abs(MathHelper.wrapAngleTo180_float(sm[0] - baseYaw)) <= tol
                        && Math.abs(sm[1] - basePitch) <= tol) {
                    this.hitAt = hitBlock;
                    this.hitSide = side;
                    this.placeAt = mop.hitVec;
                    this.placeQueued = true;
                }
            }
        }

        event.setRotation(sm[0], sm[1], ROTATION_PRIORITY);
        event.setPervRotation(sm[0], ROTATION_PRIORITY);

        if (this.prePlaceAssist(baseYaw, basePitch, event)) {
            // pre-place aim overrides when active (same order as Raven's two handlers)
        }
    }

    /**
     * Raven's second onClientRotation handler (pre-place aim). Returns true
     * when it overrode the rotation.
     */
    private boolean prePlaceAssist(float baseYaw, float basePitch, UpdateEvent event) {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            return false;
        }

        float currentPitch = basePitch;
        double reach = mc.playerController.getBlockReachDistance();

        TargetResult target = findTarget(currentPitch, reach);
        if (target == null) {
            return false;
        }

        float[] sm = smoothRotation(baseYaw, basePitch, target.yaw, target.pitch, 15, 20.0F);
        event.setRotation(sm[0], sm[1], ROTATION_PRIORITY);
        event.setPervRotation(sm[0], ROTATION_PRIORITY);
        return true;
    }

    private void runTargetSelection() {
        clearAim();

        if (!isActivationPressed() || mc.currentScreen != null) {
            disablePlacing();
            this.circleProgress = 0.0F;
            return;
        }

        int strongSlot = pickBlockSlot(true);
        int weakSlot = pickBlockSlot(false);
        if (strongSlot == -1 && weakSlot == -1) {
            disablePlacing();
            return;
        }

        this.plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);

        if (!getTarget()) {
            disablePlacing();
            return;
        }

        if (this.lastTargetAdjacent) {
            this.plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);
        } else {
            this.plannedSlot = (weakSlot != -1 ? weakSlot : strongSlot);
        }

        if (!this.placing) {
            enablePlacing();
        }

        if (mc.gameSettings.keyBindAttack.isKeyDown() || mc.gameSettings.keyBindUseItem.isKeyDown()) {
            clearAim();
        }

        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        KeyBindUtil.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        equipPlannedSlot();
    }

    @EventTarget
    public void onPlayerUpdate(PlayerUpdateEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }

        if (this.placeQueued) {
            this.placeQueued = false;
            if (this.hitAt != null && this.hitSide != null && this.placeAt != null) {
                BlockPos placementPos = this.hitAt.offset(this.hitSide);
                ItemStack held = mc.thePlayer.getHeldItem();
                if (!isNearBed(placementPos) && held != null
                        && mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, this.hitAt, this.hitSide, this.placeAt)) {
                    mc.thePlayer.swingItem();
                }
            }
        }

        this.fillCount = 0;
        this.fillTargetCount = 0;

        if (isActivationPressed() && mc.currentScreen == null) {
            BlockPos feet = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.posY),
                    MathHelper.floor_double(mc.thePlayer.posZ)
            );

            countProgressPosition(feet.up().up());

            for (EnumFacing dir : HORIZONTALS) {
                BlockPos lowerSide = feet.offset(dir);
                countProgressPosition(lowerSide);
                countProgressPosition(lowerSide.up());
            }

            if (this.fillCount != this.lastFillCount || this.fillTargetCount != this.lastFillTargetCount) {
                this.animStartProgress = this.circleProgress;
                if (this.fillTargetCount <= 0) {
                    this.animTargetProgress = 0.0F;
                } else {
                    this.animTargetProgress = Math.max(0.0F, Math.min(1.0F, this.fillCount / this.fillTargetCount));
                }
                this.animStartTime = System.currentTimeMillis();
                this.lastFillCount = this.fillCount;
                this.lastFillTargetCount = this.fillTargetCount;
            }
        } else {
            this.lastFillCount = -1;
            this.lastFillTargetCount = -1;
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null) {
            return;
        }
        if (RotationState.isActived()
                && RotationState.getPriority() == (float) ROTATION_PRIORITY
                && MoveUtil.isForwardPressed()) {
            MoveUtil.fixStrafe(RotationState.getSmoothedYaw());
        }
    }

    @EventTarget
    public void onRender2D(Render2DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (this.showProgress.getValue() == 2) {
            resetProgressFade();
            return;
        }
        if (mc.currentScreen != null) {
            resetProgressFade();
            return;
        }
        if (this.disableInCreative.getValue() && mc.thePlayer.capabilities.isCreativeMode) {
            resetProgressFade();
            return;
        }

        long elapsed = System.currentTimeMillis() - this.animStartTime;
        if (elapsed < 50L) {
            float t = (float) elapsed / 50.0F;
            this.circleProgress = lerp(this.animStartProgress, this.animTargetProgress, quadInOutEasing(t));
        } else {
            this.circleProgress = this.animTargetProgress;
        }

        boolean hasValidProgress = this.fillTargetCount > 0 && this.fillCount > 0;
        int alpha = updateProgressAlpha(hasValidProgress);
        if (alpha <= 10) {
            return;
        }

        float ratio = Math.max(0.0F, Math.min(1.0F, this.circleProgress));
        if (this.showProgress.getValue() == 1) {
            renderPercentage(ratio, alpha);
        } else {
            renderCircleProgress(ratio, alpha);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onLeftClick(LeftClickMouseEvent event) {
        if (this.isEnabled() && this.placing) {
            event.setCancelled(true);
        }
    }

    @EventTarget(Priority.HIGHEST)
    public void onRightClick(RightClickMouseEvent event) {
        if (this.isEnabled() && this.placing) {
            event.setCancelled(true);
        }
    }

    @EventTarget
    public void onSwap(SwapItemEvent event) {
        if (this.isEnabled() && this.placing && this.disableHotbarScrolling.getValue()) {
            event.setCancelled(true);
        }
    }

    private void enablePlacing() {
        if (this.placing) {
            return;
        }
        this.placing = true;
        this.slotWasSwapped = false;
        this.prevSlot = mc.thePlayer.inventory.currentItem;
    }
    private void disablePlacing() {
        if (!this.placing) {
            return;
        }
        this.placing = false;
        if (mc.thePlayer != null) {
            if (this.slotWasSwapped && this.prevSlot != -1 && this.prevSlot != mc.thePlayer.inventory.currentItem) {
                mc.thePlayer.inventory.currentItem = this.prevSlot;
            }
            if (mc.currentScreen == null) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), Mouse.isButtonDown(0));
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), Mouse.isButtonDown(1));
            }
        }
        this.slotWasSwapped = false;
        this.prevSlot = -1;
        this.plannedSlot = -1;
    }
    private void clearAim() {
        this.targetHitPos = null;
        this.targetSide = null;
    }

    private void equipPlannedSlot() {
        int cur = mc.thePlayer.inventory.currentItem;
        if (this.plannedSlot != -1 && this.plannedSlot != cur) {
            mc.thePlayer.inventory.currentItem = this.plannedSlot;
            this.slotWasSwapped = true;
        }
    }

    private boolean isIgnoredStack(ItemStack stack) {
        if (!this.ignoreBlocks.getValue() || stack == null) {
            return false;
        }
        String filter = this.ignoredBlocks.getValue();
        if (filter == null || filter.trim().isEmpty()) {
            return false;
        }
        String[] tokens = filter.toLowerCase().split(",");
        String stackName = stack.getDisplayName().toLowerCase();
        String registryName = "";
        String unlocalized = "";
        if (stack.getItem() instanceof ItemBlock) {
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            try {
                Object id = Block.blockRegistry.getNameForObject(block);
                registryName = String.valueOf(id).toLowerCase();
            } catch (Exception ignored) {
            }
            try {
                unlocalized = block.getUnlocalizedName().toLowerCase();
            } catch (Exception ignored) {
            }
        } else {
            try {
                unlocalized = stack.getItem().getUnlocalizedName().toLowerCase();
            } catch (Exception ignored) {
            }
        }
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (stackName.contains(t) || registryName.contains(t) || unlocalized.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private int pickBlockSlot(boolean preferStrong) {
        int best = -1;
        float bestScore = preferStrong ? -1.0F : Float.MAX_VALUE;

        for (int slot = 8; slot >= 0; --slot) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[slot];
            if (s == null || s.stackSize == 0) {
                continue;
            }
            if (!(s.getItem() instanceof ItemBlock)) {
                continue;
            }
            if (isIgnoredStack(s)) {
                continue;
            }

            Block block = ((ItemBlock) s.getItem()).getBlock();
            if (block instanceof BlockLadder) {
                continue;
            }

            float score = getFistBreakTicks(block);
            if (preferStrong ? score > bestScore : score < bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private boolean getTarget() {
        AimResult result = roofAim();
        if (result == null) {
            result = sidesAim();
        }
        if (result == null) {
            return false;
        }

        BlockPos placed = result.supportBlock.offset(result.face);
        this.lastTargetAdjacent = isDirectAdjacentPlacement(placed);

        this.targetHitPos = result.supportBlock;
        this.targetSide = result.face;
        this.aimYaw = result.yaw;
        this.aimPitch = result.pitch;
        return true;
    }

    private AimResult roofAim() {
        Vec3 pos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        BlockPos aboveHead = new BlockPos(
                MathHelper.floor_double(pos.xCoord),
                MathHelper.floor_double(pos.yCoord) + 2,
                MathHelper.floor_double(pos.zCoord)
        );
        if (!isReplaceable(aboveHead)) {
            return null;
        }

        if (this.plannedSlot < 0 || this.plannedSlot > 8) {
            return null;
        }
        ItemStack held = mc.thePlayer.inventory.mainInventory[this.plannedSlot];
        double r = REACH;
        Vec3 eye = new Vec3(pos.xCoord, pos.yCoord + mc.thePlayer.getEyeHeight(), pos.zCoord);
        double r2 = r * r;
        double rp12 = (r + 1) * (r + 1);

        int minY = MathHelper.floor_double(eye.yCoord) + 1;
        int maxY = MathHelper.floor_double(eye.yCoord + r);
        int minX = MathHelper.floor_double(eye.xCoord - r);
        int maxX = MathHelper.floor_double(eye.xCoord + r);
        int minZ = MathHelper.floor_double(eye.zCoord - r);
        int maxZ = MathHelper.floor_double(eye.zCoord + r);

        ArrayList<BlockCandidate> cands = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = (x + 0.5) - eye.xCoord;
                    double dy = (y + 0.5) - eye.yCoord;
                    double dz = (z + 0.5) - eye.zCoord;
                    if (dx * dx + dy * dy + dz * dz > rp12) {
                        continue;
                    }

                    BlockPos bp = new BlockPos(x, y, z);
                    if (isReplaceable(bp)) {
                        continue;
                    }
                    Block block = getBlock(bp);
                    if (isInteractable(block) || block instanceof BlockFence || block instanceof BlockWall) {
                        continue;
                    }

                    double d2 = dist2PointAABB(eye, bp);
                    if (d2 > r2) {
                        continue;
                    }

                    cands.add(new BlockCandidate(d2, bp));
                }
            }
        }

        cands.sort((a, b) -> Double.compare(a.dist, b.dist));

        for (BlockCandidate cand : cands) {
            AimResult res = getBestRotationsToBlock(held, cand.pos, eye, r, minY);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    private AimResult getBestRotationsToBlock(ItemStack held, BlockPos targetCell, Vec3 eye, double reachVal, int minY) {
        float baseYaw = mc.thePlayer.rotationYaw;
        float basePitch = mc.thePlayer.rotationPitch;

        boolean faceUp = Math.abs(eye.yCoord - (targetCell.getY() + 1)) < Math.abs(eye.yCoord - targetCell.getY());
        boolean faceSouth = Math.abs(eye.zCoord - (targetCell.getZ() + 1)) < Math.abs(eye.zCoord - targetCell.getZ());
        boolean faceEast = Math.abs(eye.xCoord - (targetCell.getX() + 1)) < Math.abs(eye.xCoord - targetCell.getX());

        double bx = targetCell.getX(), by = targetCell.getY(), bz = targetCell.getZ();
        double jit = GRID_STEP * 0.1;

        ArrayList<RotationCandidate> cands = new ArrayList<>((GRID_N + 1) * (GRID_N + 1) * 3 + 1);
        cands.add(new RotationCandidate(0, baseYaw, basePitch));

        for (int row = 0; row <= GRID_N; row++) {
            double v = clamp01(row * GRID_STEP + jitter(jit));
            for (int col = 0; col <= GRID_N; col++) {
                double u = clamp01(col * GRID_STEP + jitter(jit));

                float[] rY = getRotationsFromEye(eye,
                        bx + u, faceUp ? by + 1 - GRID_INSET : by + GRID_INSET, bz + v);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rY[0] - baseYaw)) + Math.abs(rY[1] - basePitch),
                        rY[0], rY[1]));

                float[] rZ = getRotationsFromEye(eye,
                        bx + u, by + v, faceSouth ? bz + 1 - GRID_INSET : bz + GRID_INSET);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rZ[0] - baseYaw)) + Math.abs(rZ[1] - basePitch),
                        rZ[0], rZ[1]));

                float[] rX = getRotationsFromEye(eye,
                        faceEast ? bx + 1 - GRID_INSET : bx + GRID_INSET, by + v, bz + u);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rX[0] - baseYaw)) + Math.abs(rX[1] - basePitch),
                        rX[0], rX[1]));
            }
        }

        cands.sort((a, b) -> Double.compare(a.cost, b.cost));

        int byY = targetCell.getY();
        for (RotationCandidate c : cands) {
            MovingObjectPosition mop = rayCastBlock(reachVal, c.yaw, c.pitch);
            if (mop == null) {
                continue;
            }
            BlockPos hitBlock = mop.getBlockPos();
            EnumFacing face = mop.sideHit;
            if (hitBlock.equals(targetCell) && hitBlock.getY() >= minY
                    && !(face == EnumFacing.DOWN && byY == minY)
                    && canPlaceBlockOnSide(held, hitBlock, face)) {
                BlockPos placementPos = hitBlock.offset(face);
                if (isNearBed(placementPos)) {
                    continue;
                }
                return new AimResult(hitBlock, face, c.yaw, c.pitch);
            }
        }
        return null;
    }

    private void countProgressPosition(BlockPos pos) {
        if (isNearBed(pos)) {
            return;
        }
        this.fillTargetCount++;
        if (!isReplaceable(pos)) {
            this.fillCount++;
        }
    }

    private AimResult sidesAim() {
        BlockPos feet = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        BlockPos head = feet.up();
        double r = REACH;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);

        ArrayList<BlockPos> baseline = new ArrayList<>(8);
        for (EnumFacing dir : HORIZONTALS) {
            baseline.add(feet.offset(dir));
            baseline.add(head.offset(dir));
        }

        ArrayList<BlockPos> primaryGoals = new ArrayList<>(baseline.size());
        for (BlockPos pos : baseline) {
            if (!isReplaceable(pos)) {
                continue;
            }
            if (!hasReplaceableNeighbor(pos, feet, head)) {
                continue;
            }
            primaryGoals.add(pos);
        }
        if (primaryGoals.isEmpty()) {
            return null;
        }

        Vec3 enemyPos = getClosestPlayerPos(100.0);
        if (enemyPos != null) {
            baseline.sort((a, b) -> {
                double da = sq(a.getX() + 0.5 - enemyPos.xCoord)
                        + sq(a.getY() + 0.5 - enemyPos.yCoord)
                        + sq(a.getZ() + 0.5 - enemyPos.zCoord);
                double db = sq(b.getX() + 0.5 - enemyPos.xCoord)
                        + sq(b.getY() + 0.5 - enemyPos.yCoord)
                        + sq(b.getZ() + 0.5 - enemyPos.zCoord);
                return Double.compare(da, db);
            });
            int picked = 0;
            for (int i = 0; i < baseline.size() && picked < 3; i++) {
                BlockPos pos = baseline.get(i);
                if (!isReplaceable(pos)) {
                    continue;
                }
                if (!hasReplaceableNeighbor(pos, feet, head)) {
                    continue;
                }
                AimResult enemyResult = findBestForGoals(Collections.singletonList(pos), r, eye);
                if (enemyResult != null) {
                    return enemyResult;
                }
                picked++;
            }
        }

        AimResult result = findBestForGoals(primaryGoals, r, eye);
        if (result != null) {
            return result;
        }

        ArrayList<BlockPos> frontier = new ArrayList<>(primaryGoals);
        HashSet<Long> seen = new HashSet<>(frontier.size() * 8);
        for (BlockPos g : frontier) {
            seen.add(g.toLong());
        }

        for (int iter = 0; iter < 5; iter++) {
            if (frontier.isEmpty()) {
                break;
            }
            ArrayList<BlockPos> layer = new ArrayList<>(frontier.size() * 3);
            for (BlockPos g : frontier) {
                for (EnumFacing f : EnumFacing.values()) {
                    BlockPos s = g.offset(f);
                    if (!isReplaceable(s)) {
                        continue;
                    }
                    if (!seen.add(s.toLong())) {
                        continue;
                    }
                    layer.add(s);
                }
            }
            if (!layer.isEmpty()) {
                AimResult layerResult = findBestForGoals(layer, r, eye);
                if (layerResult != null) {
                    return layerResult;
                }
            }
            frontier = layer;
        }
        return null;
    }

    private boolean hasReplaceableNeighbor(BlockPos pos, BlockPos... exclude) {
        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos neighbor = pos.offset(facing);
            if (!isReplaceable(neighbor)) {
                continue;
            }
            boolean excluded = false;
            for (BlockPos excludedPos : exclude) {
                if (neighbor.equals(excludedPos)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                return true;
            }
        }
        return false;
    }

    private AimResult findBestForGoals(List<BlockPos> goals, double reachVal, Vec3 eye) {
        if (goals == null || goals.isEmpty()) {
            return null;
        }
        if (this.plannedSlot < 0 || this.plannedSlot > 8) {
            return null;
        }

        ItemStack held = mc.thePlayer.inventory.mainInventory[this.plannedSlot];
        float curYaw = mc.thePlayer.rotationYaw;
        float curPitch = mc.thePlayer.rotationPitch;

        MovingObjectPosition now = rayCastBlock(reachVal, curYaw, curPitch);
        if (now != null) {
            BlockPos support = now.getBlockPos();
            EnumFacing faceHit = now.sideHit;
            if (!isReplaceable(support) && canPlaceBlockOnSide(held, support, faceHit)) {
                for (BlockPos goal : goals) {
                    AimResult ok = tryPlacement(reachVal, curYaw, curPitch, support, faceHit, goal);
                    if (ok != null) {
                        return ok;
                    }
                }
            }
        }

        double jit = GRID_STEP * 0.1;
        double insetTop = 1 - GRID_INSET - 1e-3;
        double insetBot = GRID_INSET + 1e-3;

        ArrayList<PlacementCandidate> cands = new ArrayList<>(Math.max(16, goals.size() * 6 * (GRID_N + 1) * (GRID_N + 1)));

        for (BlockPos g : goals) {
            for (SupportOffset s : SUPPORTS) {
                BlockPos support = new BlockPos(g.getX() + s.dx, g.getY() + s.dy, g.getZ() + s.dz);
                if (isReplaceable(support) || !canPlaceBlockOnSide(held, support, s.face)) {
                    continue;
                }

                double sx = support.getX(), sy = support.getY(), sz = support.getZ();

                for (int row = 0; row <= GRID_N; row++) {
                    boolean ltr = (row & 1) == 0;
                    double v = clamp01(row * GRID_STEP + jitter(jit));

                    for (int col = 0; col <= GRID_N; col++) {
                        double cu = clamp01(col * GRID_STEP + jitter(jit));
                        double u = ltr ? cu : 1.0 - cu;

                        double px, py, pz;
                        if (s.dy != 0) {
                            px = sx + u;
                            pz = sz + v;
                            py = sy + (s.dy < 0 ? insetTop : insetBot);
                        } else if (s.dz != 0) {
                            px = sx + u;
                            py = sy + v;
                            pz = sz + (s.dz < 0 ? insetTop : insetBot);
                        } else {
                            pz = sz + u;
                            py = sy + v;
                            px = sx + (s.dx < 0 ? insetTop : insetBot);
                        }

                        float[] rot = getRotationsFromEye(eye, px, py, pz);
                        float dYaw = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - curYaw));
                        float dPit = Math.abs(rot[1] - curPitch);
                        if (dYaw < 0.1F && dPit < 0.1F) {
                            continue;
                        }

                        cands.add(new PlacementCandidate(dYaw + dPit, rot[0], rot[1], support, s.face, g));
                    }
                }
            }
        }

        if (cands.isEmpty()) {
            return null;
        }

        cands.sort((a, b) -> Double.compare(a.cost, b.cost));

        for (PlacementCandidate c : cands) {
            AimResult ok = tryPlacement(reachVal, c.yaw, c.pitch, c.support, c.face, c.goal);
            if (ok != null) {
                return ok;
            }
        }
        return null;
    }

    private AimResult tryPlacement(double reachVal, float yaw, float pit,
                                   BlockPos expectedSupport, EnumFacing expectedFace,
                                   BlockPos goal) {
        MovingObjectPosition mop = rayCastBlock(reachVal, yaw, pit);
        if (mop == null) {
            return null;
        }

        BlockPos hitBlock = mop.getBlockPos();
        EnumFacing faceHit = mop.sideHit;

        if (!hitBlock.equals(expectedSupport)) {
            return null;
        }
        if (faceHit != expectedFace) {
            return null;
        }

        BlockPos placed = hitBlock.offset(faceHit);
        if (!placed.equals(goal)) {
            return null;
        }
        if (isNearBed(placed)) {
            return null;
        }

        return new AimResult(hitBlock, faceHit, yaw, pit);
    }

    private TargetResult findTarget(float currentPitch, double reach) {
        float yaw = mc.thePlayer.rotationYaw;

        net.minecraft.util.AxisAlignedBB bbox = mc.thePlayer.getEntityBoundingBox();
        int standY = MathHelper.floor_double(bbox.minY) - 1;
        int minX = MathHelper.floor_double(bbox.minX);
        int maxX = MathHelper.floor_double(bbox.maxX);
        int minZ = MathHelper.floor_double(bbox.minZ);
        int maxZ = MathHelper.floor_double(bbox.maxZ);

        ArrayList<FaceTarget> targets = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos standBlock = new BlockPos(x, standY, z);
                if (isReplaceable(standBlock)) {
                    continue;
                }
                for (EnumFacing face : HORIZONTALS) {
                    BlockPos placed = standBlock.offset(face);
                    if (!isReplaceable(placed)) {
                        continue;
                    }
                    targets.add(new FaceTarget(standBlock, face));
                }
            }
        }
        if (targets.isEmpty()) {
            return null;
        }

        float bestDelta = Float.MAX_VALUE;
        float bestPitch = Float.NaN;
        BlockPos bestSupport = null;
        EnumFacing bestFace = null;
        float randScale = 0.2F;

        for (float pitch = 60.0F; pitch <= 90.0F;) {
            float step = 1.0F + (float) (Math.random() * 2 - 1) * (0.3F + randScale * 0.4F);
            if (step < 0.4F) {
                step = 0.4F;
            }
            if (step > 1.8F) {
                step = 1.8F;
            }
            pitch += step;
            float samplePitch = Math.min(pitch, 90.0F);
            MovingObjectPosition mop = rayCastBlock(reach, yaw, samplePitch);
            if (mop == null) {
                continue;
            }
            EnumFacing hitFace = mop.sideHit;
            if (hitFace == EnumFacing.UP || hitFace == EnumFacing.DOWN) {
                continue;
            }

            BlockPos hitBlock = mop.getBlockPos();
            for (FaceTarget t : targets) {
                if (hitBlock.equals(t.block) && hitFace == t.face) {
                    float delta = Math.abs(samplePitch - currentPitch);
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestPitch = samplePitch;
                        bestSupport = t.block;
                        bestFace = t.face;
                    }
                    break;
                }
            }
            if (pitch >= 90.0F) {
                break;
            }
        }

        if (bestSupport == null || bestFace == null || Float.isNaN(bestPitch)) {
            return null;
        }
        return new TargetResult(yaw, bestPitch, bestSupport, bestFace);
    }

    private boolean isDirectAdjacentPlacement(BlockPos p) {
        BlockPos feet = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        int dx = p.getX() - feet.getX();
        int dy = p.getY() - feet.getY();
        int dz = p.getZ() - feet.getZ();
        if (dx == 0 && dz == 0 && dy == 2) {
            return true;
        }
        return (dy == 0 || dy == 1)
                && ((Math.abs(dx) == 1 && dz == 0) || (Math.abs(dz) == 1 && dx == 0));
    }

    private boolean isNearBed(BlockPos placementPos) {
        if (!this.skipNearBed.getValue()) {
            return false;
        }
        if (getBlock(placementPos) instanceof BlockBed) {
            return true;
        }
        for (EnumFacing facing : EnumFacing.values()) {
            if (getBlock(placementPos.offset(facing)) instanceof BlockBed) {
                return true;
            }
        }
        return false;
    }

    private Vec3 getClosestPlayerPos(double maxDistSq) {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        Vec3 closest = null;
        double bestDist = maxDistSq;
        for (net.minecraft.entity.player.EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) {
                continue;
            }
            if (mc.getNetHandler() == null || mc.getNetHandler().getPlayerInfo(player.getUniqueID()) == null) {
                continue;
            }
            double dx = player.posX - mc.thePlayer.posX;
            double dy = player.posY - mc.thePlayer.posY;
            double dz = player.posZ - mc.thePlayer.posZ;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                closest = new Vec3(player.posX, player.posY, player.posZ);
            }
        }
        return closest;
    }

    private int updateProgressAlpha(boolean shouldShow) {
        long now = System.currentTimeMillis();
        if (shouldShow) {
            this.progressFadeOutStart = -1;
            if (this.progressFadeInStart < 0 && this.previousProgressAlpha < 255.0F) {
                this.progressFadeInStart = now;
            }
            float alpha;
            if (this.progressFadeInStart >= 0) {
                alpha = fadeSample(this.progressFadeInStart, 10.0F, 255.0F);
                if (alpha >= 255.0F) {
                    alpha = 255.0F;
                    this.progressFadeInStart = -1;
                }
            } else {
                alpha = 255.0F;
            }
            this.previousProgressAlpha = alpha;
            return (int) alpha;
        }

        if (this.previousProgressAlpha <= 10.0F) {
            resetProgressFade();
            return 0;
        }
        if (this.progressFadeOutStart < 0) {
            this.progressFadeOutStart = now;
            this.progressFadeInStart = -1;
        }
        float alpha = 255.0F - fadeSample(this.progressFadeOutStart, 0.0F, 255.0F);
        if (alpha <= 10.0F) {
            resetProgressFade();
            return 0;
        }
        this.previousProgressAlpha = alpha;
        return (int) alpha;
    }

    private static float fadeSample(long startMs, float begin, float end) {
        float t = (float) (System.currentTimeMillis() - startMs) / 150.0F;
        if (t <= 0.0F) {
            return begin;
        }
        if (t >= 1.0F) {
            return end;
        }
        return begin + cubicEaseInOut(t) * (end - begin);
    }

    private static float cubicEaseInOut(float t) {
        return t < 0.5F ? 4.0F * t * t * t : (t - 1.0F) * (2.0F * t - 2.0F) * (2.0F * t - 2.0F) + 1.0F;
    }

    private void renderCircleProgress(float ratio, int alpha) {
        ScaledResolution resolution = new ScaledResolution(mc);

        float centerX = resolution.getScaledWidth() / 2.0F + 0.5F;
        float centerY = resolution.getScaledHeight() / 2.0F + 0.5F;
        float radius = 10.0F;
        float thickness = 3.0F;
        float alphaFloat = alpha / 255.0F;

        draw2DCircle(centerX, centerY, radius, 100, thickness, 0.0F, 0.0F, 0.0F, alphaFloat * 0.5F);

        if (ratio >= 0.999F) {
            draw2DCircle(centerX, centerY, radius, 100, thickness, 0.0F, 1.0F, 0.0F, alphaFloat);
            return;
        }

        int red = (int) ((1.0F - ratio) * 255.0F + 0.5F);
        int green = (int) (ratio * 255.0F + 0.5F);
        int color = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8);

        float startAngle = 90.0F;
        float endAngle = startAngle + ratio * 360.0F + 0.5F;
        draw2DCircleArc(centerX, centerY, radius, startAngle, endAngle, thickness, color);
    }

    private void renderPercentage(float ratio, int alpha) {
        ScaledResolution resolution = new ScaledResolution(mc);
        String text = Math.round(ratio * 100.0F) + "%";
        float x = resolution.getScaledWidth() / 2.0F - mc.fontRendererObj.getStringWidth(text) / 2.0F;
        float y = resolution.getScaledHeight() / 2.0F + 12.0F;
        int color = ((ratio >= 0.999F ? 0x00FF00 : 0xFFFFFF) & 0xFFFFFF) | (alpha << 24);

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawStringWithShadow(text, x, y, color);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private void resetProgressFade() {
        this.progressFadeInStart = -1;
        this.progressFadeOutStart = -1;
        this.previousProgressAlpha = 0.0F;
    }

    private static void draw2DCircle(float centerX, float centerY, float radius, int segments,
                                     float lineWidth, float r, float g, float b, float a) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(r, g, b, a);
        GL11.glLineWidth(lineWidth);

        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i <= segments; i++) {
            double theta = 2 * Math.PI * i / segments;
            GL11.glVertex2f((float) (radius * Math.cos(theta)) + centerX, (float) (radius * Math.sin(theta)) + centerY);
        }
        GL11.glEnd();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glLineWidth(1);
        GL11.glPopMatrix();
    }

    private static void draw2DCircleArc(float centerX, float centerY, float radius,
                                        float startAngle, float endAngle, float lineWidth, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = ((color >> 24) & 0xFF) / 255.0F;

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(r, g, b, a);
        GL11.glLineWidth(lineWidth);

        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (float angle = startAngle; angle <= endAngle; angle += 1) {
            double theta = Math.toRadians(angle + 180);
            GL11.glVertex2f((float) (radius * Math.cos(theta)) + centerX, (float) (radius * Math.sin(theta)) + centerY);
        }
        GL11.glEnd();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glLineWidth(1);
        GL11.glPopMatrix();
    }

    // ---- Ported Raven helpers (RotationUtils / BlockUtils / Utils) ----

    private static float[] smoothRotation(float baseYaw, float basePitch,
                                          float targetYaw, float targetPitch,
                                          int speed, float randomizationPercent) {
        if (speed <= 0) {
            return new float[]{baseYaw, MathHelper.clamp_float(basePitch, -90.0F, 90.0F)};
        }
        if (speed >= 30) {
            return new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -90.0F, 90.0F)};
        }
        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
        float deltaPitch = targetPitch - basePitch;
        float magnitude = (float) MathHelper.sqrt_double(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (magnitude < 0.001F) {
            return new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -90.0F, 90.0F)};
        }
        float t = speed / 30.0F;
        float stepSize = t * t * 180.0F;
        float range = 0.6F * (randomizationPercent / 100.0F);
        float multiplier = (range <= 0.001F) ? 1.0F : (1.0F - range / 2.0F + (float) (Math.random() * range));
        stepSize *= multiplier;
        float proximityFactor = Math.min(1.0F, magnitude / FAR_THRESHOLD);
        proximityFactor = (float) Math.pow(proximityFactor, 0.7);
        float maxSlowdown = randomizationPercent / 100.0F;
        float proximityMult = Math.max(0.8F, 1.0F - maxSlowdown * (1.0F - proximityFactor));
        stepSize *= proximityMult;
        float stepLength = Math.min(stepSize, magnitude);
        float scale = stepLength / magnitude;
        return new float[]{baseYaw + deltaYaw * scale,
                MathHelper.clamp_float(basePitch + deltaPitch * scale, -90.0F, 90.0F)};
    }

    private static MovingObjectPosition rayCastBlock(double distance, float yaw, float pitch) {
        Vec3 eyeVec = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 lookVec = getLookVec(yaw, pitch);
        Vec3 sumVec = eyeVec.addVector(lookVec.xCoord * distance, lookVec.yCoord * distance, lookVec.zCoord * distance);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyeVec, sumVec, false, false, false);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        return mop;
    }

    private static Vec3 getLookVec(float yaw, float pitch) {
        float f = MathHelper.cos(-yaw * ((float) Math.PI / 180.0F) - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * ((float) Math.PI / 180.0F) - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * ((float) Math.PI / 180.0F));
        float f3 = MathHelper.sin(-pitch * ((float) Math.PI / 180.0F));
        return new Vec3(f1 * f2, f3, f * f2);
    }

    private static float[] getRotationsFromEye(Vec3 eye, double tx, double ty, double tz) {
        double dx = tx - eye.xCoord;
        double dy = ty - eye.yCoord;
        double dz = tz - eye.zCoord;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        return new float[]{yaw, pitch};
    }

    private static Block getBlock(BlockPos pos) {
        return mc.theWorld.getBlockState(pos).getBlock();
    }

    private static boolean isReplaceable(BlockPos pos) {
        return getBlock(pos).isReplaceable(mc.theWorld, pos);
    }

    private static boolean isInteractable(Block block) {
        return block instanceof net.minecraft.block.BlockTrapDoor
                || block instanceof net.minecraft.block.BlockDoor
                || block instanceof net.minecraft.block.BlockContainer
                || block instanceof net.minecraft.block.BlockJukebox
                || block instanceof net.minecraft.block.BlockFenceGate
                || block instanceof net.minecraft.block.BlockChest
                || block instanceof net.minecraft.block.BlockEnderChest
                || block instanceof net.minecraft.block.BlockEnchantmentTable
                || block instanceof net.minecraft.block.BlockBrewingStand
                || block instanceof BlockBed
                || block instanceof net.minecraft.block.BlockDropper
                || block instanceof net.minecraft.block.BlockDispenser
                || block instanceof net.minecraft.block.BlockHopper
                || block instanceof net.minecraft.block.BlockAnvil
                || block instanceof net.minecraft.block.BlockNote
                || block instanceof net.minecraft.block.BlockWorkbench;
    }

    private static double dist2PointAABB(Vec3 p, BlockPos b) {
        double cx = Math.max(b.getX(), Math.min(b.getX() + 1, p.xCoord));
        double cy = Math.max(b.getY(), Math.min(b.getY() + 1, p.yCoord));
        double cz = Math.max(b.getZ(), Math.min(b.getZ() + 1, p.zCoord));
        double dx = p.xCoord - cx, dy = p.yCoord - cy, dz = p.zCoord - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean canPlaceBlockOnSide(ItemStack stack, BlockPos pos, EnumFacing side) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        return ((ItemBlock) stack.getItem()).canPlaceBlockOnSide(mc.theWorld, pos, side, mc.thePlayer, stack);
    }

    private static float getFistBreakTicks(Block block) {
        float hardness = block.getBlockHardness(mc.theWorld, null);
        if (hardness < 0) {
            return Float.MAX_VALUE;
        }
        if (hardness == 0) {
            return 0;
        }
        return hardness * (block.getMaterial().isToolNotRequired() ? 30.0F : 100.0F);
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private static float quadInOutEasing(float t) {
        if (t < 0.5F) {
            return 2.0F * t * t;
        }
        return -1.0F + (4.0F - 2.0F * t) * t;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double jitter(double range) {
        return range > 0 ? (Math.random() * 2 - 1) * range : 0;
    }

    private static final class SupportOffset {
        final int dx, dy, dz;
        final EnumFacing face;

        SupportOffset(int dx, int dy, int dz, EnumFacing face) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.face = face;
        }
    }

    private static final class BlockCandidate {
        final double dist;
        final BlockPos pos;

        BlockCandidate(double dist, BlockPos pos) {
            this.dist = dist;
            this.pos = pos;
        }
    }

    private static final class RotationCandidate {
        final double cost;
        final float yaw, pitch;

        RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class PlacementCandidate {
        final double cost;
        final float yaw, pitch;
        final BlockPos support, goal;
        final EnumFacing face;

        PlacementCandidate(double cost, float yaw, float pitch, BlockPos support, EnumFacing face, BlockPos goal) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
            this.support = support;
            this.face = face;
            this.goal = goal;
        }
    }

    private static final class AimResult {
        final BlockPos supportBlock;
        final EnumFacing face;
        final float yaw, pitch;

        AimResult(BlockPos supportBlock, EnumFacing face, float yaw, float pitch) {
            this.supportBlock = supportBlock;
            this.face = face;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final class FaceTarget {
        final BlockPos block;
        final EnumFacing face;

        FaceTarget(BlockPos block, EnumFacing face) {
            this.block = block;
            this.face = face;
        }
    }

    private static final class TargetResult {
        final float yaw, pitch;
        final BlockPos support;
        final EnumFacing face;

        TargetResult(float yaw, float pitch, BlockPos support, EnumFacing face) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.support = support;
            this.face = face;
        }
    }
}
