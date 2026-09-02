package keystrokesmod.module.impl.network;

import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorS14PacketEntity;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.render.TargetHUD;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.*;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {
    private static final long POSITION_INTERP_MS = 80L;
    private static final double POS_EPS = 1.0e-6;
    private static final double MOVEMENT_DISTANCE_EPS = 0.001D;

    private final SliderSetting minDistance;
    private final SliderSetting maxDistance;
    private final SliderSetting maxDelay;
    private final SliderSetting maxHurtTime;
    private final SliderSetting cooldown;

    private final ButtonSetting disableAdventure;
    private final ButtonSetting flushOnDamage;
    private final ButtonSetting ignoreTeammates;
    private final ButtonSetting weaponOnly;

    private final ButtonSetting showServerPosition;
    private final ColorSetting positionColor;

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();

    private Vec3 targetPos = null;
    private Vec3 lastTargetSnapshot = null;
    private EntityPlayer target = null;
    private int currentDelay = 0;
    private long lastDeactivationTime = 0;
    private long lastQueuedReleaseAt = 0L;
    private boolean delayingPackets = false;
    private boolean wasActive = false;

    private Vec3 positionInterpFrom;
    private Vec3 positionInterpTo;
    private long positionInterpStartMs;

    public Backtrack() {
        super("Backtrack", category.network);
        this.liteModule = true;
        this.registerSetting(maxDelay = new SliderSetting("Delay", "ms",200.0, 50.0, 500.0, 10.0));
        this.registerSetting(minDistance = new SliderSetting("Distance (min)", 0.0, 0.0, 3.0, 0.1));
        this.registerSetting(maxDistance = new SliderSetting("Distance (max)", 4.0, 1.0, 6.0, 0.1));
        this.registerSetting(cooldown = new SliderSetting("Cooldown", "ms", 0.0, 0.0, 2000.0, 50.0));
        this.registerSetting(maxHurtTime = new SliderSetting("Max hurt time", "ms", 500.0, 0.0, 500.0, 10.0));
        this.registerSetting(disableAdventure = new ButtonSetting("Disable adventure", true));
        this.registerSetting(flushOnDamage = new ButtonSetting("Flush on damage", true));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(showServerPosition = new ButtonSetting("Show server position", true));
        this.registerSetting(positionColor = new ColorSetting("Position color", 0, 0, 250, 100));
    }

    @Override
    public void guiUpdate() {
        Utils.correctValue(minDistance, maxDistance);
    }

    @Override
    public void onEnable() {
        clearPendingPackets();
        targetPos = null;
        lastTargetSnapshot = null;
        target = null;
        currentDelay = 0;
        lastDeactivationTime = 0;
        delayingPackets = false;
        wasActive = false;
        clearPositionInterp();
    }

    @Override
    public void onDisable() {
        if (mc.isSingleplayer()) {
            resetWithoutProcessingPackets();
        }
        else {
            releaseAll();
        }
        target = null;
        targetPos = null;
        lastTargetSnapshot = null;
        currentDelay = 0;
        clearPositionInterp();
    }

    @Override
    public void onUpdate() {
        if (mc.isSingleplayer()) {
            resetWithoutProcessingPackets();
            return;
        }

        if (mc.thePlayer == null || mc.theWorld == null) {
            releaseAll();
            return;
        }
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            releaseAll();
            return;
        }

        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) {
            releaseAll();
            return;
        }

        if (flushOnDamage.isToggled() && mc.thePlayer.hurtTime > 0) {
            releaseAll();
            return;
        }

        // Check distance to target position
        if (targetPos != null && target != null) {
            double distance = RotationUtils.distanceFromEyeToClosestOnAABB(target, targetPos);
            if (distance > maxDistance.getInput() || distance < minDistance.getInput()) {
                if (wasActive) {
                    releaseAll();
                    lastDeactivationTime = System.currentTimeMillis();
                    wasActive = false;
                }
            }
        }

        // Process delayed packets that have reached their delay
        processDelayedPackets();

        // Update targetPos to current target position if queue is empty
        if (isPacketQueueEmpty() && target != null) {
            targetPos = target.getPositionVector();
        }

        // Track if we're active
        wasActive = currentDelay > 0 && !isPacketQueueEmpty();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (mc.isSingleplayer()) return;
        if (!showServerPosition.isToggled()) return;
        if (target == null || targetPos == null || target.isDead) return;
        if (currentDelay <= 0) return;

        long nowMs = System.currentTimeMillis();
        if (positionInterpTo == null) {
            positionInterpFrom = targetPos;
            positionInterpTo = targetPos;
            positionInterpStartMs = nowMs;
        }
        else if (positionChanged(targetPos, positionInterpTo)) {
            double elapsedProgress = Math.min(1.0D,
                    (nowMs - positionInterpStartMs) / (double) POSITION_INTERP_MS);
            positionInterpFrom = lerpVec3(positionInterpFrom, positionInterpTo, elapsedProgress);
            positionInterpTo = targetPos;
            positionInterpStartMs = nowMs;
        }

        double progress = Math.min(1.0D,
                (nowMs - positionInterpStartMs) / (double) POSITION_INTERP_MS);
        Vec3 drawPos = lerpVec3(positionInterpFrom, positionInterpTo, progress);

        int color = positionColor.getColor();

        TargetHUD targetHUD =ModuleManager.targetHUD;

        if (targetHUD.isEspActiveFor(target)) {
            color = targetHUD.getCurrentEspColor(positionColor.getAlpha()).getRGB();
        }

        RenderUtils.drawPlayerBoundingBox(drawPos, color);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent e) {
        if (mc.isSingleplayer()) return;
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            return;
        }
        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) {
            return;
        }

        // Check cooldown
        if (cooldown.getInput() > 0 && lastDeactivationTime > 0) {
            long elapsed = System.currentTimeMillis() - lastDeactivationTime;
            if (elapsed < cooldown.getInput()) {
                return; // Still in cooldown
            }
        }

        if (!(e.target instanceof EntityPlayer)) {
            return;
        }

        EntityPlayer attacked = (EntityPlayer) e.target;

        if (AntiBot.isBot(attacked) || (Utils.isTeammate(attacked) && ignoreTeammates.isToggled()) || Utils.isFriended(attacked)) {
            return;
        }

        // Check distance
        double distance = mc.thePlayer.getDistanceToEntity(attacked);
        if (distance > maxDistance.getInput() || distance < minDistance.getInput()) {
            return;
        }

        // Check target hurt time (in ms, convert to ticks approximately)
        // 1 tick = 50ms, so hurtTime * 50 gives approximate ms
        if (maxHurtTime.getInput() < 500) {
            int hurtTimeMs = attacked.hurtTime * 50;
            if (hurtTimeMs > maxHurtTime.getInput()) {
                return; // Target was hit too recently
            }
        }

        if (target == null || attacked != target) {
            releaseAll();
            targetPos = attacked.getPositionVector();
        }

        target = attacked;

        // Set delay
        currentDelay = (int) maxDelay.getInput();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onReceivePacket(ReceivePacketEvent e) {
        if (mc.isSingleplayer()) return;
        if (!Utils.nullCheck()) return;
        if (mc.thePlayer.ticksExisted < 20) {
            clearPendingPackets();
            delayingPackets = false;
            return;
        }

        Packet packet = e.getPacket();

        // Release expired packets before handling the newest packet so the
        // original inbound order is retained as closely as possible.
        processDelayedPackets();

        // Check weapons only
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            releaseAll();
            return;
        }

        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) {
            releaseAll();
            return;
        }

        // Check disable on hit
        if (flushOnDamage.isToggled() && mc.thePlayer.hurtTime > 0) {
            releaseAll();
            return;
        }

        // No target = release all
        if (target == null) {
            releaseAll();
            return;
        }

        // A disconnect must never remain in the delayed queue.
        if (packet instanceof S40PacketDisconnect) {
            releaseAll();
            target = null;
            targetPos = null;
            return;
        }

        // Handle entity destroy
        if (packet instanceof S13PacketDestroyEntities) {
            S13PacketDestroyEntities destroyPacket = (S13PacketDestroyEntities) packet;
            for (int id : destroyPacket.getEntityIDs()) {
                if (target != null && id == target.getEntityId()) {
                    target = null;
                    targetPos = null;
                    releaseAll();
                    return;
                }
            }
        }

        if (currentDelay <= 0) {
            return;
        }

        Vec3 nextTargetSnapshot = null;

        // Target movement controls whether the complete inbound stream is
        // delayed. Holding transactions with the movement keeps the server's
        // latency-compensated entity state aligned with the client's state.
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity movePacket = (S14PacketEntity) packet;
            if (((IAccessorS14PacketEntity) movePacket).getEntityId() == target.getEntityId()) {
                Vec3 position = targetPos != null ? targetPos : target.getPositionVector();
                nextTargetSnapshot = position.addVector(
                        movePacket.func_149062_c() / 32.0D,
                        movePacket.func_149061_d() / 32.0D,
                        movePacket.func_149064_e() / 32.0D
                );
            }
        }

        // Handle entity teleport
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleportPacket = (S18PacketEntityTeleport) packet;
            if (teleportPacket.getEntityId() == target.getEntityId()) {
                nextTargetSnapshot = new Vec3(
                        teleportPacket.getX() / 32.0D,
                        teleportPacket.getY() / 32.0D,
                        teleportPacket.getZ() / 32.0D
                );
            }
        }

        if (nextTargetSnapshot != null) {
            delayingPackets = isMovingAway(lastTargetSnapshot, nextTargetSnapshot);
            targetPos = nextTargetSnapshot;
            lastTargetSnapshot = nextTargetSnapshot;
        }

        // Corrections and local-player velocity must be applied immediately.
        // Older delayed packets are replayed first to preserve packet order.
        if (shouldFlushForPacket(packet)) {
            flushPendingPackets();
            return;
        }

        if (delayingPackets) {
            queuePacket(packet);
            e.setCanceled(true);
        }
        else if (!isPacketQueueEmpty()) {
            flushPendingPackets();
        }
    }

    private boolean shouldFlushForPacket(Packet packet) {
        if (packet instanceof S08PacketPlayerPosLook) {
            return true;
        }
        if (packet instanceof S12PacketEntityVelocity && mc.thePlayer != null) {
            return ((S12PacketEntityVelocity) packet).getEntityID() == mc.thePlayer.getEntityId();
        }
        return false;
    }

    private boolean isMovingAway(Vec3 previousSnapshot, Vec3 newSnapshot) {
        if (previousSnapshot == null || newSnapshot == null || target == null) {
            return false;
        }

        double previousDistance = RotationUtils.distanceFromEyeToClosestOnAABB(target, previousSnapshot);
        double newDistance = RotationUtils.distanceFromEyeToClosestOnAABB(target, newSnapshot);
        return newDistance > previousDistance + MOVEMENT_DISTANCE_EPS;
    }

    private void processDelayedPackets() {
        synchronized (packetQueue) {
            long now = System.currentTimeMillis();
            while (!packetQueue.isEmpty()) {
                TimedPacket tp = packetQueue.peek();
                if (tp == null || now < tp.releaseAt) {
                    break;
                }

                packetQueue.poll();
                processPacket(tp.packet);
            }
            if (packetQueue.isEmpty()) {
                lastQueuedReleaseAt = 0L;
            }
        }
    }

    private void queuePacket(Packet packet) {
        synchronized (packetQueue) {
            long releaseAt = System.currentTimeMillis() + currentDelay;
            if (lastQueuedReleaseAt > 0L && releaseAt <= lastQueuedReleaseAt) {
                releaseAt = lastQueuedReleaseAt + 1L;
            }
            packetQueue.add(new TimedPacket(packet, releaseAt));
            lastQueuedReleaseAt = releaseAt;
        }
    }

    private void releaseAll() {
        flushPendingPackets();
        currentDelay = 0;
        lastTargetSnapshot = null;
        delayingPackets = false;
        clearPositionInterp();
    }

    private void flushPendingPackets() {
        synchronized (packetQueue) {
            while (!packetQueue.isEmpty()) {
                TimedPacket tp = packetQueue.poll();
                if (tp != null) {
                    processPacket(tp.packet);
                }
            }
            lastQueuedReleaseAt = 0L;
        }
    }

    private void clearPendingPackets() {
        synchronized (packetQueue) {
            packetQueue.clear();
            lastQueuedReleaseAt = 0L;
        }
    }

    private boolean isPacketQueueEmpty() {
        synchronized (packetQueue) {
            return packetQueue.isEmpty();
        }
    }

    private void resetWithoutProcessingPackets() {
        clearPendingPackets();
        target = null;
        targetPos = null;
        lastTargetSnapshot = null;
        currentDelay = 0;
        lastDeactivationTime = 0;
        delayingPackets = false;
        wasActive = false;
        clearPositionInterp();
    }

    private void clearPositionInterp() {
        positionInterpFrom = null;
        positionInterpTo = null;
        positionInterpStartMs = 0L;
    }

    private static boolean positionChanged(Vec3 a, Vec3 b) {
        return Math.abs(a.xCoord - b.xCoord) > POS_EPS
                || Math.abs(a.yCoord - b.yCoord) > POS_EPS
                || Math.abs(a.zCoord - b.zCoord) > POS_EPS;
    }

    private static Vec3 lerpVec3(Vec3 from, Vec3 to, double progress) {
        if (progress <= 0.0D) {
            return from;
        }
        if (progress >= 1.0D) {
            return to;
        }
        return new Vec3(
                from.xCoord + (to.xCoord - from.xCoord) * progress,
                from.yCoord + (to.yCoord - from.yCoord) * progress,
                from.zCoord + (to.zCoord - from.zCoord) * progress
        );
    }

    @SuppressWarnings("unchecked")
    private void processPacket(Object packet) {
        if (packet == null) return;

        try {
            if (packet instanceof Packet && mc.getNetHandler() != null) {
                ((Packet<INetHandlerPlayClient>) packet).processPacket(mc.getNetHandler());
            }
        }
        catch (Exception e) {}
    }

    public boolean isRenderingServerPositionFor(EntityLivingBase entity) {
        return !mc.isSingleplayer() && this.showServerPosition.isToggled() && this.target == entity && this.targetPos != null && !this.target.isDead && this.currentDelay > 0;
    }

    public Vec3 getBacktrackPosition() {
        if (target == null || targetPos == null || target.isDead || currentDelay <= 0) {
            return null;
        }
        return targetPos;
    }

    public EntityPlayer getBacktrackTarget() {
        if (target == null || target.isDead || currentDelay <= 0) {
            return null;
        }
        return target;
    }

    @Override
    public String getInfo() {
        if (currentDelay > 0 && !isPacketQueueEmpty()) {
            return currentDelay + "ms";
        }
        return (int) maxDelay.getInput() + "ms";
    }

    private static class TimedPacket {
        final Object packet;
        final long releaseAt;

        TimedPacket(Object packet, long releaseAt) {
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }
}
