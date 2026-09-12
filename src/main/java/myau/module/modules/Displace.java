package myau.module.modules;

import myau.Myau;
import myau.event.EventTarget;
import myau.event.types.EventType;
import myau.events.MoveInputEvent;
import myau.events.PacketEvent;
import myau.events.Render3DEvent;
import myau.events.TickEvent;
import myau.events.UpdateEvent;
import myau.mixin.IAccessorRenderManager;
import myau.module.Module;
import myau.property.properties.BooleanProperty;
import myau.property.properties.FloatProperty;
import myau.property.properties.IntProperty;
import myau.property.properties.ModeProperty;
import myau.util.ItemUtil;
import myau.util.RotationUtil;
import myau.util.TeamUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Port of {@code keystrokesmod.module.impl.combat.Displace} (raven-bS-beta)
 * into the Myau/RottenApple module framework.
 *
 * <p>Original: 1877 lines, Raven-specific APIs (Raven, ClientRotationEvent,
 * GameTickEvent, PostPlayerInputEvent, PreAttackEvent, PrePlayerInteractEvent,
 * SendPacketEvent, RotationHelper, LagRequest/ModuleBackedTimeout,
 * CombatTargeting, ModuleUtils, PacketUtils, RotationUtils, Utils).</p>
 *
 * <p>Adaptations for this client:</p>
 * <ul>
 *   <li>{@code SliderSetting}/{@code ButtonSetting} mapped to
 *       {@link ModeProperty}/{@link FloatProperty}/{@link IntProperty}/{@link BooleanProperty}.</li>
 *   <li>{@code ClientRotationEvent} emulated with {@link UpdateEvent} PRE
 *       ({@code event.setRotation(yaw, pitch, priority)}).</li>
 *   <li>{@code GameTickEvent} emulated with {@link TickEvent}.</li>
 *   <li>{@code PostPlayerInputEvent} emulated with {@link MoveInputEvent}.</li>
 *   <li>{@code SendPacketEvent} emulated with {@link PacketEvent} + {@link EventType#SEND}.</li>
 *   <li>{@code PreAttackEvent}/{@code PrePlayerInteractEvent} emulated by
 *       watching {@code C02PacketUseEntity} ATTACK packets.</li>
 *   <li>Outbound blink ({@code LagRequest}) emulated with
 *       {@code Myau.lagManager.setDelay(ticks)} (see {@link myau.management.LagManager}).</li>
 *   <li>{@code CombatTargeting} rebuilt from {@link RotationUtil} +
 *       {@link TeamUtil} (same pattern as AimAssist/LagRange).</li>
 *   <li>{@code Utils.holdingWeapon()} mapped to {@link ItemUtil#isHoldingSword()}.</li>
 *   <li>1.8.9 collision API: {@code World#getCollisionBoxes} does not exist on
 *       1.8.9, so {@code getCollidingBoundingBoxes(entity, box)} is used.</li>
 *   <li>Raven DEBUG-only void-scan visualisation removed (no {@code Raven.DEBUG}
 *       flag in this client).</li>
 *   <li>Override-attack simplified to a small flick/attack/restore state machine
 *       driven by {@link UpdateEvent} + {@link PacketEvent}.</li>
 * </ul>
 */
public class Displace extends Module {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final int DISPLACE_WINDOW_TICKS = 10;
    private static final int DISPLACEMENT_LOCK_IDLE_TICKS = 20;
    private static final int VOID_SCAN_DIRECTIONS = 48;
    private static final double VOID_SCAN_STEP = 0.5D;
    private static final double VOID_COLLISION_STEP = 0.25D;
    private static final double VOID_COLLISION_INSET = 0.03D;
    private static final double VOID_REFINEMENT_STEP = 1.5D;
    private static final double VOID_SCORE_EPSILON = 1.0E-4D;
    private static final long ARROW_FADE_MS = 250L;
    private static final double ARROW_BASE_GAP = 0.3D;
    private static final long ARROW_NUDGE_MS = 320L;
    private static final double ARROW_NUDGE_DISTANCE = 0.3D;
    private static final double ARROW_NUDGE_DECAY = 6.0D;
    private static final double ARROW_NUDGE_FADE_START = 0.58D;
    private static final double ARROW_NUDGE_FADE_DECAY = 5.0D;
    private static final int OVERRIDE_MAX_FLICK_TICKS = 5;
    private static final int OVERRIDE_ATTACK_TICKS = 2;
    private static final int OVERRIDE_RESTORE_TICKS = 2;
    private static final float OVERRIDE_FLICK_TOLERANCE = 6.0F;
    private static final double OVERRIDE_TARGET_RANGE = 5.0D;
    private static final double TARGET_RANGE = 5.0D;
    private static final int BLINK_DELAY_TICKS = 2;

    private static final String[] MODES = {"Offset", "Void"};
    private static final String[] DIRECTIONS = {"Left", "Right"};

    public final ModeProperty mode = new ModeProperty("mode", 0, MODES);
    public final FloatProperty yawOffset = new FloatProperty("yaw-offset", 90.0F, 0.0F, 180.0F, () -> this.mode.getValue() == 0);
    public final FloatProperty scanRadius = new FloatProperty("scan-radius", 6.0F, 1.0F, 12.0F, () -> this.mode.getValue() == 1);
    public final IntProperty delay = new IntProperty("delay", 0, 0, 500);
    public final ModeProperty direction = new ModeProperty("direction", 0, DIRECTIONS, () -> this.mode.getValue() == 0);
    public final BooleanProperty findVoid = new BooleanProperty("find-void", false, () -> this.mode.getValue() == 0);
    public final BooleanProperty blink = new BooleanProperty("blink", false);
    public final BooleanProperty ignoreOneBlockWall = new BooleanProperty("ignore-one-block-wall", false, () -> this.mode.getValue() == 1);
    public final BooleanProperty ignoreTeammates = new BooleanProperty("ignore-teammates", true);
    public final BooleanProperty onlyKnockbackItems = new BooleanProperty("only-knockback-items", false);
    public final BooleanProperty overrideAttack = new BooleanProperty("override-attack", false);
    public final BooleanProperty renderArrow = new BooleanProperty("render-arrow", true);
    public final BooleanProperty weaponOnly = new BooleanProperty("weapon-only", false);

    private boolean displaceThisTick = false;
    private boolean active = false;
    private boolean hasKB = false;
    private boolean compensateNextTick = false;
    private boolean displaceLeft = false;
    private boolean wasDisplacingLastTick = false;
    private int tickCounter = 0;
    private final Map<Integer, Integer> targetWindowStartTicks = new HashMap<>();
    private final Map<Integer, DisplacementLock> targetDisplacementLocks = new HashMap<>();

    private EntityPlayer arrowPlayer;
    private float arrowYaw;
    private float arrowFadeStartAlpha;
    private float arrowFadeEndAlpha;
    private long arrowFadeStartMs;
    private boolean arrowVisible;
    private long arrowNudgeStartMs = -1L;
    private int arrowNudgePlayerId = -1;

    private OverrideAttackState overrideAttackState = OverrideAttackState.IDLE;
    private EntityPlayer overrideTarget;
    private float overrideTargetYaw;
    private float overrideTargetPitch;
    private float overrideFlickYaw;
    private float overrideFlickOffset;
    private boolean overrideAbsoluteFlickYaw;
    private int overrideStateTicks;
    private long lastOverrideFlickMs = -1L;

    private enum OverrideAttackState {
        IDLE,
        FLICKING_AWAY,
        ATTACKING,
        RESTORING
    }

    private static final class DisplacementLock {
        private final float yaw;
        private int lastUseTick;
        private boolean airborne;

        private DisplacementLock(float yaw, int lastUseTick, boolean airborne) {
            this.yaw = yaw;
            this.lastUseTick = lastUseTick;
            this.airborne = airborne;
        }
    }

    public Displace() {
        super("Displace", false);
    }

    @Override
    public String[] getSuffix() {
        return new String[]{this.mode.getModeString()};
    }

    @Override
    public void onEnabled() {
        resetState();
    }

    @Override
    public void onDisabled() {
        resetState();
        Myau.lagManager.setDelay(0);
    }

    private void resetState() {
        this.displaceThisTick = false;
        this.active = false;
        this.hasKB = false;
        this.compensateNextTick = false;
        this.wasDisplacingLastTick = false;
        this.tickCounter = 0;
        this.targetWindowStartTicks.clear();
        this.targetDisplacementLocks.clear();
        this.overrideAttackState = OverrideAttackState.IDLE;
        this.overrideTarget = null;
        this.overrideStateTicks = 0;
        clearArrow();
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0D) {
            return 0;
        }
        return (int) Math.ceil(ms / 50.0D);
    }

    private boolean anyMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean isVoidMode() {
        return this.mode.getValue() == 1;
    }

    private boolean passesItemConditions() {
        if (this.onlyKnockbackItems.getValue() && EnchantmentHelper.getKnockbackModifier(mc.thePlayer) <= 0) {
            return false;
        }
        return !this.weaponOnly.getValue() || ItemUtil.isHoldingSword();
    }

    private boolean isValidTarget(EntityPlayer player) {
        if (player == null || player == mc.thePlayer || player == mc.thePlayer.ridingEntity) {
            return false;
        }
        if (player == mc.getRenderViewEntity() || player == mc.getRenderViewEntity().ridingEntity) {
            return false;
        }
        if (player.deathTime > 0 || player.isDead) {
            return false;
        }
        if (RotationUtil.distanceToEntity(player) > TARGET_RANGE) {
            return false;
        }
        if (TeamUtil.isFriend(player)) {
            return false;
        }
        if (this.ignoreTeammates.getValue() && TeamUtil.isSameTeam(player)) {
            return false;
        }
        return !TeamUtil.isBot(player);
    }

    private EntityPlayer getTarget() {
        if (mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        // Prefer KillAura's current target when available (mirrors Raven's KillAura.attackingEntity usage).
        try {
            KillAura killAura = (KillAura) Myau.moduleManager.modules.get(KillAura.class);
            if (killAura != null && killAura.isEnabled() && killAura.getTarget() instanceof EntityPlayer) {
                EntityPlayer auraTarget = (EntityPlayer) killAura.getTarget();
                if (isValidTarget(auraTarget)) {
                    return auraTarget;
                }
            }
        } catch (Exception ignored) {
        }
        // Fall back to mouse-over entity, then nearest valid player.
        if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityPlayer) {
            EntityPlayer mouseTarget = (EntityPlayer) mc.objectMouseOver.entityHit;
            if (isValidTarget(mouseTarget)) {
                return mouseTarget;
            }
        }
        List<EntityPlayer> players = mc.theWorld.loadedEntityList.stream()
                .filter(entity -> entity instanceof EntityPlayer)
                .map(entity -> (EntityPlayer) entity)
                .filter(this::isValidTarget)
                .sorted(Comparator.comparingDouble(RotationUtil::distanceToEntity))
                .collect(Collectors.toList());
        return players.isEmpty() ? null : players.get(0);
    }

    // ---- Void scanning (ported from Raven, minus DEBUG visualisation) ----

    private boolean tryFindVoidDirection(EntityPlayer target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) {
            return false;
        }
        dx /= dist;
        dz /= dist;
        double rightX = -dz;
        double rightZ = dx;
        double eyeY = target.posY + (double) target.getEyeHeight();
        int leftVoidCount = 0;
        int rightVoidCount = 0;
        for (int i = 1; i <= 12; i++) {
            double off = i * 0.5D;
            double rx = target.posX + rightX * off;
            double rz = target.posZ + rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(rx, eyeY, rz), new Vec3(rx, eyeY - 10.0D, rz)) == null) {
                rightVoidCount++;
            }
            double lx = target.posX - rightX * off;
            double lz = target.posZ - rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(lx, eyeY, lz), new Vec3(lx, eyeY - 10.0D, lz)) == null) {
                leftVoidCount++;
            }
        }
        if (leftVoidCount == 0 && rightVoidCount == 0) {
            return false;
        }
        if (leftVoidCount != rightVoidCount) {
            this.displaceLeft = leftVoidCount > rightVoidCount;
        }
        return true;
    }

    private Float findBestVoidYaw(EntityPlayer target, float playerYaw) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }
        float offset = this.yawOffset.getValue();
        float preferredYawPositive = MathHelper.wrapAngleTo180_float(playerYaw + offset);
        float preferredYawNegative = MathHelper.wrapAngleTo180_float(playerYaw - offset);
        double scanAngleStep = 360.0D / (double) VOID_SCAN_DIRECTIONS;
        double scanDistance = this.scanRadius.getValue();
        Map<Long, Boolean> voidColumns = new HashMap<>();
        Map<Long, VoidNeighborhood> voidNeighborhoods = new HashMap<>();
        VoidYawCandidate best = null;
        for (int directionIndex = 0; directionIndex < VOID_SCAN_DIRECTIONS; directionIndex++) {
            float candidateYaw = MathHelper.wrapAngleTo180_float(preferredYawPositive + (float) (directionIndex * scanAngleStep));
            best = selectBetterVoidYaw(best, candidateYaw, preferredYawPositive, preferredYawNegative,
                    target, scanDistance, voidColumns, voidNeighborhoods);
        }
        if (best == null) {
            return null;
        }
        float coarseYaw = best.yaw;
        for (double refinementOffset = -scanAngleStep + VOID_REFINEMENT_STEP;
             refinementOffset < scanAngleStep;
             refinementOffset += VOID_REFINEMENT_STEP) {
            if (Math.abs(refinementOffset) < VOID_SCORE_EPSILON) {
                continue;
            }
            float candidateYaw = MathHelper.wrapAngleTo180_float(coarseYaw + (float) refinementOffset);
            best = selectBetterVoidYaw(best, candidateYaw, preferredYawPositive, preferredYawNegative,
                    target, scanDistance, voidColumns, voidNeighborhoods);
        }
        return best.yaw;
    }

    private VoidYawCandidate selectBetterVoidYaw(VoidYawCandidate currentBest, float candidateYaw,
                                                 float preferredYawPositive, float preferredYawNegative,
                                                 EntityPlayer target, double scanDistance,
                                                 Map<Long, Boolean> voidColumns,
                                                 Map<Long, VoidNeighborhood> voidNeighborhoods) {
        double radians = Math.toRadians(candidateYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double positiveDistance = Math.abs(MathHelper.wrapAngleTo180_float(candidateYaw - preferredYawPositive));
        double negativeDistance = Math.abs(MathHelper.wrapAngleTo180_float(candidateYaw - preferredYawNegative));
        double priorityDistance = Math.min(positiveDistance, negativeDistance);
        VoidPathScore pathScore = scoreVoidPath(target, forwardX, forwardZ, scanDistance, voidColumns, voidNeighborhoods);
        if (pathScore == null) {
            return currentBest;
        }
        VoidYawCandidate candidate = new VoidYawCandidate(candidateYaw, pathScore, priorityDistance);
        if (currentBest == null || candidate.compareTo(currentBest) > 0) {
            return candidate;
        }
        return currentBest;
    }

    private VoidPathScore scoreVoidPath(EntityPlayer target, double forwardX, double forwardZ,
                                        double scanDistance, Map<Long, Boolean> voidColumns,
                                        Map<Long, VoidNeighborhood> voidNeighborhoods) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        double sideOffset = Math.max(0.2D, (double) target.width * 0.45D);
        double checkedForward = 0.0D;
        double pathQuality = 0.0D;
        int consecutiveCenterVoid = 0;
        int longestCenterVoidRun = 0;
        int bestNeighborhoodQuality = -1;
        int bestDestinationWidth = 0;
        double bestDestinationDistance = Double.MAX_VALUE;
        double bestDestinationX = 0.0D;
        double bestDestinationZ = 0.0D;
        VoidNeighborhood bestNeighborhood = null;
        AxisAlignedBB collisionBox = target.getEntityBoundingBox().contract(VOID_COLLISION_INSET, 0.001D, VOID_COLLISION_INSET);

        for (double forward = VOID_SCAN_STEP; forward <= scanDistance + VOID_SCORE_EPSILON; forward += VOID_SCAN_STEP) {
            double blockedDistance = getVoidPathBlockedDistance(collisionBox, forwardX, forwardZ, checkedForward, forward);
            if (blockedDistance >= 0.0D) {
                break;
            }
            checkedForward = forward;
            double centerX = target.posX + forwardX * forward;
            double centerZ = target.posZ + forwardZ * forward;
            boolean centerVoid = isVoidColumn(centerX, target.posY, centerZ, voidColumns);
            boolean leftVoid = isVoidColumn(centerX - sideX * sideOffset, target.posY, centerZ - sideZ * sideOffset, voidColumns);
            boolean rightVoid = isVoidColumn(centerX + sideX * sideOffset, target.posY, centerZ + sideZ * sideOffset, voidColumns);
            double distanceWeight = scanDistance + VOID_SCAN_STEP - forward;
            if (leftVoid) {
                pathQuality += distanceWeight;
            }
            if (rightVoid) {
                pathQuality += distanceWeight;
            }
            if (centerVoid) {
                consecutiveCenterVoid++;
                longestCenterVoidRun = Math.max(longestCenterVoidRun, consecutiveCenterVoid);
                pathQuality += distanceWeight * 1.6D + (double) consecutiveCenterVoid * 2.0D;
                VoidNeighborhood neighborhood = getVoidNeighborhood(centerX, target.posY, centerZ, voidColumns, voidNeighborhoods);
                int neighborhoodQuality = neighborhood.quality;
                int destinationWidth = 1 + (leftVoid ? 1 : 0) + (rightVoid ? 1 : 0);
                if (neighborhoodQuality > bestNeighborhoodQuality
                        || (neighborhoodQuality == bestNeighborhoodQuality && destinationWidth > bestDestinationWidth)
                        || (neighborhoodQuality == bestNeighborhoodQuality && destinationWidth == bestDestinationWidth && forward < bestDestinationDistance)) {
                    bestNeighborhoodQuality = neighborhoodQuality;
                    bestDestinationWidth = destinationWidth;
                    bestDestinationDistance = forward;
                    bestDestinationX = centerX;
                    bestDestinationZ = centerZ;
                    bestNeighborhood = neighborhood;
                }
            } else {
                consecutiveCenterVoid = 0;
            }
        }
        if (bestNeighborhoodQuality < 0 || bestNeighborhood == null) {
            return null;
        }
        return new VoidPathScore(bestNeighborhood.immediateVoidNeighbors,
                bestNeighborhood.extendedVoidNeighbors, bestDestinationWidth,
                longestCenterVoidRun, pathQuality, bestDestinationDistance,
                bestDestinationX, bestDestinationZ, bestNeighborhood.blockX, bestNeighborhood.blockZ,
                bestNeighborhood.voidMask);
    }

    private VoidNeighborhood getVoidNeighborhood(double x, double y, double z,
                                                 Map<Long, Boolean> voidColumns,
                                                 Map<Long, VoidNeighborhood> voidNeighborhoods) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        VoidNeighborhood cached = voidNeighborhoods.get(columnKey);
        if (cached != null) {
            return cached;
        }
        int immediateVoidNeighbors = 0;
        int extendedVoidNeighbors = 0;
        long voidMask = 0L;
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int radius = Math.max(Math.abs(offsetX), Math.abs(offsetZ));
                if (radius == 0) {
                    continue;
                }
                if (isVoidColumn(blockX + offsetX + 0.5D, y, blockZ + offsetZ + 0.5D, voidColumns)) {
                    int bitIndex = (offsetX + 2) * 5 + offsetZ + 2;
                    voidMask |= 1L << bitIndex;
                    if (radius == 1) {
                        immediateVoidNeighbors++;
                    } else {
                        extendedVoidNeighbors++;
                    }
                }
            }
        }
        int quality = immediateVoidNeighbors * 100 + extendedVoidNeighbors;
        VoidNeighborhood neighborhood = new VoidNeighborhood(blockX, blockZ, immediateVoidNeighbors,
                extendedVoidNeighbors, quality, voidMask);
        voidNeighborhoods.put(columnKey, neighborhood);
        return neighborhood;
    }

    private double getVoidPathBlockedDistance(AxisAlignedBB collisionBox, double forwardX, double forwardZ,
                                              double fromForward, double toForward) {
        for (double forward = fromForward + VOID_COLLISION_STEP; forward <= toForward + VOID_SCORE_EPSILON; forward += VOID_COLLISION_STEP) {
            AxisAlignedBB checkBox = collisionBox.offset(forwardX * forward, 0.0D, forwardZ * forward);
            BlockPos minCorner = new BlockPos(checkBox.minX, checkBox.minY, checkBox.minZ);
            BlockPos maxCorner = new BlockPos(checkBox.maxX, checkBox.maxY, checkBox.maxZ);
            if (!mc.theWorld.isBlockLoaded(minCorner) || !mc.theWorld.isBlockLoaded(maxCorner)) {
                return forward;
            }
            // 1.8.9 API: getCollidingBoundingBoxes(Entity, AxisAlignedBB).
            List<AxisAlignedBB> blockCollisions = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, checkBox);
            if (!blockCollisions.isEmpty() && (!this.ignoreOneBlockWall.getValue() || !isOneBlockWall(checkBox, blockCollisions))) {
                return forward;
            }
        }
        return -1.0D;
    }

    private boolean isOneBlockWall(AxisAlignedBB checkBox, List<AxisAlignedBB> blockCollisions) {
        double maximumWallY = checkBox.minY + 1.0D + VOID_SCORE_EPSILON;
        for (AxisAlignedBB collision : blockCollisions) {
            if (collision.maxY > maximumWallY) {
                return false;
            }
        }
        return true;
    }

    private boolean isVoidColumn(double x, double y, double z, Map<Long, Boolean> voidColumns) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        Boolean cached = voidColumns.get(columnKey);
        if (cached != null) {
            return cached;
        }
        int startY = Math.min(255, MathHelper.floor_double(y - 0.01D));
        BlockPos probe = new BlockPos(blockX, Math.max(0, startY), blockZ);
        if (!mc.theWorld.isBlockLoaded(probe)) {
            voidColumns.put(columnKey, false);
            return false;
        }
        for (int blockY = startY; blockY >= 0; blockY--) {
            if (!mc.theWorld.isAirBlock(new BlockPos(blockX, blockY, blockZ))) {
                voidColumns.put(columnKey, false);
                return false;
            }
        }
        voidColumns.put(columnKey, true);
        return true;
    }

    private long getColumnKey(int blockX, int blockZ) {
        return ((long) blockX << 32) ^ ((long) blockZ & 0xffffffffL);
    }

    private void updateDisplaceSide(float playerYaw, float displaceYaw) {
        this.displaceLeft = MathHelper.wrapAngleTo180_float(displaceYaw - playerYaw) < 0.0F;
    }

    private static final class VoidYawCandidate {
        private final float yaw;
        private final VoidPathScore pathScore;
        private final double priorityDistance;

        private VoidYawCandidate(float yaw, VoidPathScore pathScore, double priorityDistance) {
            this.yaw = yaw;
            this.pathScore = pathScore;
            this.priorityDistance = priorityDistance;
        }

        private int compareTo(VoidYawCandidate other) {
            int pathComparison = this.pathScore.compareTo(other.pathScore);
            if (pathComparison != 0) {
                return pathComparison;
            }
            if (Math.abs(this.priorityDistance - other.priorityDistance) > VOID_SCORE_EPSILON) {
                return this.priorityDistance < other.priorityDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidPathScore {
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int destinationWidth;
        private final int longestCenterVoidRun;
        private final double pathQuality;
        private final double destinationDistance;
        private final double destinationX;
        private final double destinationZ;
        private final int destinationBlockX;
        private final int destinationBlockZ;
        private final long destinationVoidMask;

        private VoidPathScore(int immediateVoidNeighbors, int extendedVoidNeighbors, int destinationWidth,
                              int longestCenterVoidRun, double pathQuality, double destinationDistance,
                              double destinationX, double destinationZ, int destinationBlockX,
                              int destinationBlockZ, long destinationVoidMask) {
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.destinationWidth = destinationWidth;
            this.longestCenterVoidRun = longestCenterVoidRun;
            this.pathQuality = pathQuality;
            this.destinationDistance = destinationDistance;
            this.destinationX = destinationX;
            this.destinationZ = destinationZ;
            this.destinationBlockX = destinationBlockX;
            this.destinationBlockZ = destinationBlockZ;
            this.destinationVoidMask = destinationVoidMask;
        }

        private int compareTo(VoidPathScore other) {
            if (this.immediateVoidNeighbors != other.immediateVoidNeighbors) {
                return Integer.compare(this.immediateVoidNeighbors, other.immediateVoidNeighbors);
            }
            if (this.extendedVoidNeighbors != other.extendedVoidNeighbors) {
                return Integer.compare(this.extendedVoidNeighbors, other.extendedVoidNeighbors);
            }
            if (this.destinationWidth != other.destinationWidth) {
                return Integer.compare(this.destinationWidth, other.destinationWidth);
            }
            if (this.longestCenterVoidRun != other.longestCenterVoidRun) {
                return Integer.compare(this.longestCenterVoidRun, other.longestCenterVoidRun);
            }
            if (Math.abs(this.pathQuality - other.pathQuality) > VOID_SCORE_EPSILON) {
                return this.pathQuality > other.pathQuality ? 1 : -1;
            }
            if (Math.abs(this.destinationDistance - other.destinationDistance) > VOID_SCORE_EPSILON) {
                return this.destinationDistance < other.destinationDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidNeighborhood {
        private final int blockX;
        private final int blockZ;
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int quality;
        private final long voidMask;

        private VoidNeighborhood(int blockX, int blockZ, int immediateVoidNeighbors,
                                 int extendedVoidNeighbors, int quality, long voidMask) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.quality = quality;
            this.voidMask = voidMask;
        }
    }

    // ---- Displacement locks / delay window (ported from Raven) ----

    private void pruneTargetDelayStates() {
        if (mc.theWorld == null) {
            this.targetWindowStartTicks.clear();
            this.targetDisplacementLocks.clear();
            return;
        }
        Iterator<Map.Entry<Integer, Integer>> iterator = this.targetWindowStartTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }
        Iterator<Map.Entry<Integer, DisplacementLock>> lockIterator = this.targetDisplacementLocks.entrySet().iterator();
        while (lockIterator.hasNext()) {
            Map.Entry<Integer, DisplacementLock> entry = lockIterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                lockIterator.remove();
                continue;
            }
            EntityPlayer player = (EntityPlayer) entity;
            DisplacementLock lock = entry.getValue();
            if (!player.onGround) {
                lock.airborne = true;
            } else if (lock.airborne || this.tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS) {
                lockIterator.remove();
            }
        }
    }

    private float lockDisplacementYaw(EntityPlayer target, float yaw) {
        int targetId = target.getEntityId();
        DisplacementLock lock = this.targetDisplacementLocks.get(targetId);
        if (lock != null && ((lock.airborne && target.onGround)
                || this.tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS)) {
            this.targetDisplacementLocks.remove(targetId);
            lock = null;
        }
        if (lock == null) {
            lock = new DisplacementLock(yaw, this.tickCounter, !target.onGround);
            this.targetDisplacementLocks.put(targetId, lock);
        } else {
            lock.lastUseTick = this.tickCounter;
            if (!target.onGround) {
                lock.airborne = true;
            }
        }
        return lock.yaw;
    }

    private float getLockedDisplacementYaw(EntityPlayer target, float fallbackYaw) {
        DisplacementLock lock = this.targetDisplacementLocks.get(target.getEntityId());
        return lock == null ? fallbackYaw : lock.yaw;
    }

    private boolean shouldDisplaceInCurrentWindow(EntityPlayer target, int currentTick) {
        if (target == null) {
            return true;
        }
        int targetId = target.getEntityId();
        Integer windowStartTick = this.targetWindowStartTicks.get(targetId);
        if (windowStartTick == null || currentTick - windowStartTick >= DISPLACE_WINDOW_TICKS) {
            this.targetWindowStartTicks.put(targetId, currentTick);
            return true;
        }
        int delayTicks = msToTicks(this.delay.getValue());
        if (delayTicks <= 0) {
            return true;
        }
        return currentTick - windowStartTick >= delayTicks;
    }

    // ---- Arrow indicator (ported from Raven, debug visualisation removed) ----

    private void showArrow(EntityPlayer player, float yaw) {
        long now = System.currentTimeMillis();
        boolean playerChanged = this.arrowPlayer != player;
        float currentAlpha = playerChanged ? 0.0F : getArrowAlpha(now);
        if (playerChanged) {
            clearArrowNudge();
        }
        this.arrowPlayer = player;
        this.arrowYaw = yaw;
        if (!this.arrowVisible || playerChanged) {
            this.arrowFadeStartAlpha = currentAlpha;
            this.arrowFadeEndAlpha = 1.0F;
            this.arrowFadeStartMs = now;
        }
        this.arrowVisible = true;
    }

    private void hideArrow() {
        if (this.arrowPlayer == null || !this.arrowVisible) {
            return;
        }
        long now = System.currentTimeMillis();
        this.arrowFadeStartAlpha = getArrowAlpha(now);
        this.arrowFadeEndAlpha = 0.0F;
        this.arrowFadeStartMs = now;
        this.arrowVisible = false;
    }

    private float getArrowAlpha(long now) {
        float progress = Math.min(1.0F, Math.max(0.0F, (float) (now - this.arrowFadeStartMs) / (float) ARROW_FADE_MS));
        return this.arrowFadeStartAlpha + (this.arrowFadeEndAlpha - this.arrowFadeStartAlpha) * progress;
    }

    private void triggerArrowNudge(EntityPlayer player) {
        if (player == null || this.arrowPlayer != player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.arrowNudgePlayerId == player.getEntityId() && now - this.arrowNudgeStartMs < ARROW_NUDGE_MS) {
            return;
        }
        this.arrowNudgePlayerId = player.getEntityId();
        this.arrowNudgeStartMs = now;
    }

    private double getArrowNudgeProgress(EntityPlayer player, long now) {
        if (player == null || this.arrowNudgePlayerId != player.getEntityId() || this.arrowNudgeStartMs < 0L) {
            return -1.0D;
        }
        return Math.min(1.0D, Math.max(0.0D, (double) (now - this.arrowNudgeStartMs) / (double) ARROW_NUDGE_MS));
    }

    private double getArrowNudgeOffset(double progress) {
        if (progress < 0.0D) {
            return 0.0D;
        }
        double endValue = Math.exp(-ARROW_NUDGE_DECAY);
        double movement = (1.0D - Math.exp(-ARROW_NUDGE_DECAY * progress)) / (1.0D - endValue);
        return movement * ARROW_NUDGE_DISTANCE;
    }

    private float getArrowNudgeAlpha(double progress) {
        if (progress < 0.0D || progress <= ARROW_NUDGE_FADE_START) {
            return 1.0F;
        }
        double fadeProgress = (progress - ARROW_NUDGE_FADE_START) / (1.0D - ARROW_NUDGE_FADE_START);
        double endValue = Math.exp(-ARROW_NUDGE_FADE_DECAY);
        return (float) ((Math.exp(-ARROW_NUDGE_FADE_DECAY * fadeProgress) - endValue) / (1.0D - endValue));
    }

    private void clearArrowNudge() {
        this.arrowNudgeStartMs = -1L;
        this.arrowNudgePlayerId = -1;
    }

    private void clearArrow() {
        this.arrowPlayer = null;
        this.arrowFadeStartAlpha = 0.0F;
        this.arrowFadeEndAlpha = 0.0F;
        this.arrowFadeStartMs = 0L;
        this.arrowVisible = false;
        clearArrowNudge();
    }

    private void drawArrow(EntityPlayer player, float yaw, float partialTicks, float alpha, double nudgeOffset) {
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.height * 0.5D;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double startDistance = player.width * 0.5D + ARROW_BASE_GAP + nudgeOffset;
        double startX = x + forwardX * startDistance;
        double startZ = z + forwardZ * startDistance;
        double bodyX = startX + forwardX * 0.74D;
        double bodyZ = startZ + forwardZ * 0.74D;
        double headBaseX = startX + forwardX * 0.56D;
        double headBaseZ = startZ + forwardZ * 0.56D;
        double tipX = bodyX + forwardX * 0.52D;
        double tipZ = bodyZ + forwardZ * 0.52D;
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.82F * alpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        vertex(startX, y, startZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08D, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, -0.3D, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08D, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08D, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, 0.3D, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.95F * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        vertex(startX, y, startZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, -0.08D, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, -0.3D, viewerX, viewerY, viewerZ);
        vertex(tipX, y, tipZ, 0.0D, viewerX, viewerY, viewerZ);
        vertex(headBaseX, y, headBaseZ, 0.3D, viewerX, viewerY, viewerZ);
        vertex(bodyX, y, bodyZ, 0.08D, viewerX, viewerY, viewerZ);
        GL11.glEnd();
        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private void vertex(double x, double y, double z, double yOffset, double viewerX, double viewerY, double viewerZ) {
        GL11.glVertex3d(x - viewerX, y + yOffset - viewerY, z - viewerZ);
    }

    // ---- Simplified override attack (flick away -> attack -> restore) ----

    private boolean isOverrideTargetValid(EntityPlayer target) {
        return target != null && target.worldObj == mc.theWorld
                && RotationUtil.distanceToEntity(target) <= OVERRIDE_TARGET_RANGE
                && isValidTarget(target);
    }

    private boolean canStartOverrideAttack(EntityPlayer target) {
        if (this.overrideAttackState != OverrideAttackState.IDLE || mc.thePlayer == null
                || mc.currentScreen != null || !isOverrideTargetValid(target) || !passesItemConditions()) {
            return false;
        }
        long configuredDelay = this.delay.getValue();
        return this.lastOverrideFlickMs < 0L || configuredDelay <= 0L
                || System.currentTimeMillis() - this.lastOverrideFlickMs >= configuredDelay;
    }

    private boolean startOverrideAttack(EntityPlayer target) {
        if (!canStartOverrideAttack(target)) {
            return false;
        }
        float baseYaw = mc.thePlayer.rotationYaw;
        float basePitch = mc.thePlayer.rotationPitch;
        float[] targetRotations = RotationUtil.getRotationsToBox(
                target.getEntityBoundingBox(), baseYaw, basePitch, 180.0F, 0.0F);
        if (targetRotations == null) {
            return false;
        }
        this.overrideTargetYaw = targetRotations[0];
        this.overrideTargetPitch = targetRotations[1];
        this.overrideAbsoluteFlickYaw = isVoidMode();
        if (this.overrideAbsoluteFlickYaw) {
            Float bestVoidYaw = findBestVoidYaw(target, this.overrideTargetYaw);
            if (bestVoidYaw == null) {
                return false;
            }
            this.overrideFlickYaw = bestVoidYaw;
            this.overrideFlickOffset = 0.0F;
            updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
        } else {
            if (!this.findVoid.getValue() || !tryFindVoidDirection(target)) {
                this.displaceLeft = this.direction.getValue() == 0;
            }
            this.overrideFlickOffset = this.displaceLeft ? -this.yawOffset.getValue() : this.yawOffset.getValue();
            this.overrideFlickYaw = this.overrideTargetYaw + this.overrideFlickOffset;
        }
        this.overrideFlickYaw = lockDisplacementYaw(target, this.overrideFlickYaw);
        updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
        this.overrideTarget = target;
        this.overrideAttackState = OverrideAttackState.FLICKING_AWAY;
        this.overrideStateTicks = 0;
        this.lastOverrideFlickMs = System.currentTimeMillis();
        this.active = true;
        this.displaceThisTick = true;
        this.hasKB = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        showArrow(target, this.overrideFlickYaw);
        if (this.blink.getValue()) {
            Myau.lagManager.setDelay(BLINK_DELAY_TICKS);
        }
        return true;
    }

    private void resetOverrideAttackState() {
        this.overrideAttackState = OverrideAttackState.IDLE;
        this.overrideTarget = null;
        this.overrideStateTicks = 0;
    }

    // ---- Events ----

    @EventTarget
    public void onTick(TickEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        this.tickCounter++;
        pruneTargetDelayStates();
        if (this.overrideAttackState != OverrideAttackState.IDLE) {
            if (mc.thePlayer == null || mc.currentScreen != null || !isOverrideTargetValid(this.overrideTarget)) {
                resetOverrideAttackState();
                this.active = false;
                this.displaceThisTick = false;
                Myau.lagManager.setDelay(0);
                return;
            }
            this.overrideStateTicks++;
            switch (this.overrideAttackState) {
                case FLICKING_AWAY:
                    if (this.overrideStateTicks >= OVERRIDE_MAX_FLICK_TICKS) {
                        this.overrideAttackState = OverrideAttackState.ATTACKING;
                        this.overrideStateTicks = 0;
                        this.displaceThisTick = false;
                    }
                    break;
                case ATTACKING:
                    if (this.overrideStateTicks >= OVERRIDE_ATTACK_TICKS) {
                        this.overrideAttackState = OverrideAttackState.RESTORING;
                        this.overrideStateTicks = 0;
                    }
                    break;
                case RESTORING:
                    if (this.overrideStateTicks >= OVERRIDE_RESTORE_TICKS) {
                        resetOverrideAttackState();
                        this.active = false;
                        this.displaceThisTick = false;
                        Myau.lagManager.setDelay(0);
                    }
                    break;
                default:
                    break;
            }
        } else if (!this.blink.getValue() || !this.active || !this.displaceThisTick) {
            if (Myau.lagManager != null && this.blink.getValue()) {
                Myau.lagManager.setDelay(0);
            }
        }
    }

    @EventTarget
    public void onUpdate(UpdateEvent event) {
        if (!this.isEnabled() || event.getType() != EventType.PRE) {
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null) {
            resetState();
            return;
        }
        if (!passesItemConditions()) {
            this.active = false;
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            hideArrow();
            return;
        }

        // Simplified override-attack rotation path.
        if (this.overrideAttack.getValue() && this.overrideAttackState != OverrideAttackState.IDLE) {
            if (!isOverrideTargetValid(this.overrideTarget)) {
                resetOverrideAttackState();
                return;
            }
            float[] targetRotations = RotationUtil.getRotationsToBox(
                    this.overrideTarget.getEntityBoundingBox(), event.getYaw(), event.getPitch(), 180.0F, 0.0F);
            if (targetRotations == null) {
                resetOverrideAttackState();
                return;
            }
            this.overrideTargetYaw = targetRotations[0];
            this.overrideTargetPitch = targetRotations[1];
            if (!this.overrideAbsoluteFlickYaw) {
                this.overrideFlickYaw = getLockedDisplacementYaw(this.overrideTarget, this.overrideTargetYaw + this.overrideFlickOffset);
            }
            updateDisplaceSide(this.overrideTargetYaw, this.overrideFlickYaw);
            if (this.overrideAttackState == OverrideAttackState.FLICKING_AWAY) {
                event.setRotation(this.overrideFlickYaw, this.overrideTargetPitch, 2);
                this.displaceThisTick = true;
            } else {
                event.setRotation(this.overrideTargetYaw, this.overrideTargetPitch, 2);
                this.displaceThisTick = false;
            }
            this.active = true;
            showArrow(this.overrideTarget, this.overrideFlickYaw);
            return;
        }

        EntityPlayer target = getTarget();
        this.hasKB = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        this.active = target != null;
        if (!this.active) {
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            hideArrow();
            return;
        }

        float playerYaw = event.getYaw();
        float displaceYaw;
        if (isVoidMode()) {
            Float bestVoidYaw = findBestVoidYaw(target, playerYaw);
            if (bestVoidYaw == null) {
                this.active = false;
                this.displaceThisTick = false;
                this.compensateNextTick = false;
                this.wasDisplacingLastTick = false;
                hideArrow();
                return;
            }
            displaceYaw = bestVoidYaw;
            updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            if (!this.findVoid.getValue() || !tryFindVoidDirection(target)) {
                this.displaceLeft = this.direction.getValue() == 0;
            }
            float offset = this.yawOffset.getValue();
            displaceYaw = this.displaceLeft ? playerYaw - offset : playerYaw + offset;
        }

        this.displaceThisTick = !this.displaceThisTick;
        if (this.displaceThisTick && !shouldDisplaceInCurrentWindow(target, this.tickCounter)) {
            this.displaceThisTick = false;
            this.compensateNextTick = false;
            this.wasDisplacingLastTick = false;
            hideArrow();
            return;
        }
        if (this.displaceThisTick) {
            displaceYaw = lockDisplacementYaw(target, displaceYaw);
            updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            displaceYaw = getLockedDisplacementYaw(target, displaceYaw);
        }
        showArrow(target, displaceYaw);
        this.wasDisplacingLastTick = this.displaceThisTick;
        if (!this.displaceThisTick) {
            return;
        }
        event.setRotation(displaceYaw, event.getPitch(), 2);
        if (this.blink.getValue()) {
            Myau.lagManager.setDelay(BLINK_DELAY_TICKS);
        }
    }

    @EventTarget
    public void onMoveInput(MoveInputEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        if (this.overrideAttack.getValue() && this.overrideAttackState != OverrideAttackState.IDLE) {
            this.compensateNextTick = false;
            return;
        }
        if (!this.active) {
            this.compensateNextTick = false;
            return;
        }
        if (this.compensateNextTick && !this.displaceThisTick) {
            this.compensateNextTick = false;
            mc.thePlayer.movementInput.moveStrafe = this.displaceLeft ? -1.0F : 1.0F;
            return;
        }
        if (!this.displaceThisTick || this.hasKB || !anyMovementKey()) {
            return;
        }
        mc.thePlayer.movementInput.moveForward = 1.0F;
        this.compensateNextTick = true;
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event.isCancelled() || event.getType() != EventType.SEND) {
            return;
        }
        if (!(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        Entity attacked = packet.getEntityFromWorld(mc.theWorld);
        // Arrow nudge feedback on landing a hit on the indicated player.
        if (attacked == this.arrowPlayer) {
            triggerArrowNudge(this.arrowPlayer);
        }
        // Simplified override-attack trigger: intercept our own attack on a valid
        // player and run the flick/attack sequence instead.
        if (this.overrideAttack.getValue() && this.overrideAttackState == OverrideAttackState.IDLE
                && attacked instanceof EntityPlayer && isOverrideTargetValid((EntityPlayer) attacked)) {
            if (startOverrideAttack((EntityPlayer) attacked)) {
                event.setCancelled(true);
            }
        }
    }

    @EventTarget
    public void onRender3D(Render3DEvent event) {
        if (!this.isEnabled() || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!this.renderArrow.getValue() || this.arrowPlayer == null
                || this.arrowPlayer.isDead || this.arrowPlayer.deathTime != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        double nudgeProgress = getArrowNudgeProgress(this.arrowPlayer, now);
        float alpha = getArrowAlpha(now) * getArrowNudgeAlpha(nudgeProgress);
        if (alpha <= 0.0F) {
            return;
        }
        IAccessorRenderManager renderManager = (IAccessorRenderManager) mc.getRenderManager();
        double viewerX = renderManager.getRenderPosX();
        double viewerY = renderManager.getRenderPosY();
        double viewerZ = renderManager.getRenderPosZ();
        double x = this.arrowPlayer.lastTickPosX
                + (this.arrowPlayer.posX - this.arrowPlayer.lastTickPosX) * event.getPartialTicks() - viewerX;
        double y = this.arrowPlayer.lastTickPosY
                + (this.arrowPlayer.posY - this.arrowPlayer.lastTickPosY) * event.getPartialTicks()
                + this.arrowPlayer.height * 0.5D - viewerY;
        double z = this.arrowPlayer.lastTickPosZ
                + (this.arrowPlayer.posZ - this.arrowPlayer.lastTickPosZ) * event.getPartialTicks() - viewerZ;
        // Keep world-space math inside drawArrow; this pre-check only culls dead arrows.
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return;
        }
        drawArrow(this.arrowPlayer, this.arrowYaw, event.getPartialTicks(), alpha, getArrowNudgeOffset(nudgeProgress));
    }
}
