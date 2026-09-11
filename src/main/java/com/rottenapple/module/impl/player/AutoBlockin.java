package com.rottenapple.module.impl.player;

import com.rottenapple.bridge.MC;
import com.rottenapple.module.ButtonSetting;
import com.rottenapple.module.ItemListSetting;
import com.rottenapple.module.KeySetting;
import com.rottenapple.module.Module;
import com.rottenapple.module.SliderSetting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * AutoBlockin ported from Raven BS.
 * Port notes (dep-free agent limits, no packet/rotation/event hooks):
 * - Rotation spoofing (ClientRotationEvent yaw/pitch) is impossible, so there
 *   is no aim smoothing: placement fires when the player's REAL aim already
 *   sits on a valid face within Rotation tolerance. Speed/Randomization
 *   (smoothing params) and Show progress (GL overlay) are dropped.
 * - MouseEvent click suppression is dropped; attack/use keys are still forced
 *   down-state off while placing (same observable effect for vanilla input).
 * - Target selection (roof + sides search), slot management, Bed checks,
 *   ignore list and onPlayerRightClick placement are faithful.
 */
public class AutoBlockin extends Module {

    private static final double REACH = 4.5;
    private static final double GRID_INSET = 0.05;
    private static final double GRID_STEP = 0.2;
    private static final int GRID_N = (int) Math.round(1.0 / GRID_STEP);
    private static final String[] HORIZONTALS = {"EAST", "SOUTH", "WEST", "NORTH"};
    // dx, dy, dz, face name (mirrors Raven's SUPPORTS).
    private static final int[][] SUPPORT_DELTAS = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
    private static final String[] SUPPORT_FACES = {"DOWN", "UP", "SOUTH", "NORTH", "WEST", "EAST"};

    private final SliderSetting rotationTol;
    private final ButtonSetting disableInCreative;
    private final ButtonSetting skipNearBed;
    private final KeySetting activationKey;
    private final ButtonSetting ignoreBlocksToggle;
    private final ItemListSetting ignoredBlocks;

    private boolean placing;
    private boolean slotWasSwapped;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private boolean placeQueued;
    private boolean lastTargetAdjacent;

    private int[] targetHitPos;
    private Object targetSide;
    private float aimYaw;
    private float aimPitch;

    private int[] hitAt;
    private Object hitSide;
    private double[] placeAt;

    public AutoBlockin() {
        super("AutoBlockin", Category.player);
        this.registerSetting(rotationTol = new SliderSetting("Rotation tolerance", "°", 25, 20, 100, 1));
        this.registerSetting(disableInCreative = new ButtonSetting("Disable in creative", true));
        this.registerSetting(skipNearBed = new ButtonSetting("Skip near bed", true));
        this.registerSetting(activationKey = new KeySetting("Activation key", 0));
        this.registerSetting(ignoreBlocksToggle = new ButtonSetting("Ignore blocks", false));
        this.registerSetting(ignoredBlocks = new ItemListSetting("Items"));
    }

    @Override
    public void onDisable() {
        disablePlacing();
        placeQueued = false;
    }

    @Override
    public void guiUpdate() {
        ignoredBlocks.setVisible(ignoreBlocksToggle.isToggled(), this);
    }

    public KeySetting getActivationKey() {
        return activationKey;
    }

    @Override
    public void onTick() {
        if (!MC.nullCheck()) {
            disablePlacing();
            return;
        }
        if (disableInCreative.isToggled() && MC.cap(MC.player(), "isCreativeMode")) {
            disablePlacing();
            return;
        }
        runTargetSelection();
        if (MC.screen() != null) {
            disablePlacing();
        }
        if (placing && targetHitPos != null) {
            verifyCurrentAim();
        }
        executePlace();
    }

    private void runTargetSelection() {
        clearAim();

        if (!activationKey.isPressed() || MC.screen() != null) {
            disablePlacing();
            return;
        }

        int strongSlot = pickBlockSlot(true);
        int weakSlot = pickBlockSlot(false);
        if (strongSlot == -1 && weakSlot == -1) {
            disablePlacing();
            return;
        }

        plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);

        if (!getTarget()) {
            disablePlacing();
            return;
        }

        if (lastTargetAdjacent) {
            plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);
        } else {
            plannedSlot = (weakSlot != -1 ? weakSlot : strongSlot);
        }

        if (!placing) {
            enablePlacing();
        }

        if (MC.kbDown(MC.keyBind("keyBindAttack")) || MC.kbDown(MC.keyBind("keyBindUseItem"))) {
            clearAim();
        }

        MC.setKeyState(MC.keyCode(MC.keyBind("keyBindAttack")), false);
        MC.setKeyState(MC.keyCode(MC.keyBind("keyBindUseItem")), false);
        equipPlannedSlot();
    }

    /** Verifies the player's REAL aim against the selected target (no spoofing). */
    private void verifyCurrentAim() {
        Object p = MC.player();
        if (p == null) return;
        Object mop = rayBlock(REACH, MC.yaw(p), MC.pitch(p));
        if (mop == null) return;
        int[] hitBlock = mopIntPos(mop);
        Object side = MC.mopSide(mop);
        if (hitBlock == null || side == null) return;
        if (!samePos(hitBlock, targetHitPos) || !side.equals(targetSide)) return;
        double tol = rotationTol.getInput();
        if (Math.abs(wrap(aimYaw - MC.yaw(p))) <= tol && Math.abs(aimPitch - MC.pitch(p)) <= tol) {
            hitAt = hitBlock;
            hitSide = side;
            placeAt = MC.vecXYZ(MC.mopHitVec(mop));
            placeQueued = true;
        }
    }

    private void executePlace() {
        if (!placeQueued) {
            return;
        }
        placeQueued = false;
        if (hitAt == null || hitSide == null || placeAt == null) {
            return;
        }
        try {
            Object p = MC.player();
            Object w = MC.world();
            Object hitAtObj = MC.newPos(hitAt[0], hitAt[1], hitAt[2]);
            Object placeAtObj = MC.newVec(placeAt[0], placeAt[1], placeAt[2]);
            int[] placement = offsetPos(hitAt, hitSide);
            if (isNearBed(placement) || hitAtObj == null || placeAtObj == null) {
                return;
            }
            Object res = MC.call(MC.controller(), "onPlayerRightClick",
                    new Class<?>[]{MC.cls("net.minecraft.entity.player.EntityPlayer"),
                            MC.cls("net.minecraft.world.World"),
                            MC.cls("net.minecraft.item.ItemStack"),
                            MC.cls("net.minecraft.util.BlockPos"),
                            MC.cls("net.minecraft.util.EnumFacing"),
                            MC.cls("net.minecraft.util.Vec3")},
                    p, w, MC.getHeldItem(p), hitAtObj, hitSide, placeAtObj);
            if (Boolean.TRUE.equals(res)) {
                MC.call(p, "swingItem", new Class<?>[0]);
            }
        } catch (Throwable ignored) {
        }
    }

    private void enablePlacing() {
        if (placing) return;
        placing = true;
        slotWasSwapped = false;
        prevSlot = MC.currentItem(MC.inventory(MC.player()));
    }

    private void disablePlacing() {
        if (!placing) return;

        Object inv = MC.inventory(MC.player());
        if (slotWasSwapped && prevSlot != -1 && inv != null && prevSlot != MC.currentItem(inv)) {
            MC.setCurrentItem(inv, prevSlot);
        }

        placing = false;
        slotWasSwapped = false;
        prevSlot = -1;
        plannedSlot = -1;

        if (MC.screen() == null) {
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindAttack")), MC.mouseDown(0));
            MC.setKeyState(MC.keyCode(MC.keyBind("keyBindUseItem")), MC.mouseDown(1));
        }
    }

    private void clearAim() {
        targetHitPos = null;
        targetSide = null;
    }

    private void equipPlannedSlot() {
        Object inv = MC.inventory(MC.player());
        if (inv == null) return;
        int cur = MC.currentItem(inv);
        if (plannedSlot != -1 && plannedSlot != cur) {
            MC.setCurrentItem(inv, plannedSlot);
            slotWasSwapped = true;
        }
    }

    private int pickBlockSlot(boolean preferStrong) {
        int best = -1;
        float bestScore = preferStrong ? -1 : Float.MAX_VALUE;
        Object[] stacks = MC.mainInventory(MC.inventory(MC.player()));
        for (int slot = 8; slot >= 0; --slot) {
            if (slot >= stacks.length) continue;
            Object s = stacks[slot];
            if (s == null || MC.stackSize(s) == 0) continue;
            if (!MC.isItemInstance(MC.stackItem(s), "ItemBlock")) continue;
            if (ignoreBlocksToggle.isToggled() && ignoredBlocks.matches(s)) continue;
            Object block = MC.callAny(MC.stackItem(s), "getBlock");
            if (block == null || MC.isBlockInstance(block, "BlockLadder")) continue;
            float score = fistTicks(block);
            if (preferStrong ? score > bestScore : score < bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private float fistTicks(Object block) {
        try {
            Object h = MC.callAny(block, "getBlockHardness", MC.world(), null);
            float hardness = h instanceof Number ? ((Number) h).floatValue() : 1.0f;
            if (hardness < 0) return Float.MAX_VALUE;
            if (hardness == 0) return 0;
            Object mat = MC.callAny(block, "getMaterial");
            boolean notRequired = Boolean.TRUE.equals(MC.callAny(mat, "isToolNotRequired"));
            return hardness * (notRequired ? 30f : 100f);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private boolean getTarget() {
        AimResult result = roofAim();
        if (result == null) result = sidesAim();
        if (result == null) return false;

        int[] placed = offsetPos(result.supportBlock, result.face);
        lastTargetAdjacent = isDirectAdjacentPlacement(placed);

        targetHitPos = result.supportBlock;
        targetSide = result.face;
        aimYaw = result.yaw;
        aimPitch = result.pitch;
        return true;
    }

    private AimResult roofAim() {
        Object p = MC.player();
        Object w = MC.world();
        if (p == null || w == null) return null;
        double px = MC.posX(p), py = MC.posY(p), pz = MC.posZ(p);
        int[] aboveHead = {(int) Math.floor(px), (int) Math.floor(py) + 2, (int) Math.floor(pz)};
        if (!MC.blockReplaceable(w, MC.newPos(aboveHead[0], aboveHead[1], aboveHead[2]))) return null;
        if (plannedSlot < 0 || plannedSlot > 8) return null;

        Object held = MC.getStack(MC.inventory(p), plannedSlot);
        double r = REACH;
        double[] eye = MC.vecXYZ(MC.positionEyes(p, 1.0f));
        if (eye == null) return null;
        double r2 = r * r;
        double rp12 = (r + 1) * (r + 1);

        int minY = (int) Math.floor(eye[1]) + 1;
        int maxY = (int) Math.floor(eye[1] + r);
        int minX = (int) Math.floor(eye[0] - r);
        int maxX = (int) Math.floor(eye[0] + r);
        int minZ = (int) Math.floor(eye[2] - r);
        int maxZ = (int) Math.floor(eye[2] + r);

        ArrayList<BlockCandidate> cands = new ArrayList<BlockCandidate>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = (x + 0.5) - eye[0];
                    double dy = (y + 0.5) - eye[1];
                    double dz = (z + 0.5) - eye[2];
                    if (dx * dx + dy * dy + dz * dz > rp12) continue;
                    Object cell = MC.newPos(x, y, z);
                    if (!MC.blockReplaceable(w, cell)) {
                        Object block = MC.blockOf(MC.blockState(w, cell));
                        if (MC.isInteractableBlock(block)
                                || MC.isBlockInstance(block, "BlockFence")
                                || MC.isBlockInstance(block, "BlockWall")) continue;
                        if (dist2Point(eye, x, y, z) > r2) continue;
                        cands.add(new BlockCandidate(dist2Point(eye, x, y, z), new int[]{x, y, z}));
                    }
                }
            }
        }

        Collections.sort(cands, new Comparator<BlockCandidate>() {
            @Override
            public int compare(BlockCandidate a, BlockCandidate b) {
                return Double.compare(a.dist, b.dist);
            }
        });

        for (BlockCandidate cand : cands) {
            AimResult res = getBestRotationsToBlock(held, cand.pos, eye, r, minY);
            if (res != null) return res;
        }
        return null;
    }

    private AimResult getBestRotationsToBlock(Object held, int[] targetCell, double[] eye, double reachVal, int minY) {
        float baseYaw = MC.yaw(MC.player());
        float basePitch = MC.pitch(MC.player());

        boolean faceUp = Math.abs(eye[1] - (targetCell[1] + 1)) < Math.abs(eye[1] - targetCell[1]);
        boolean faceSouth = Math.abs(eye[2] - (targetCell[2] + 1)) < Math.abs(eye[2] - targetCell[2]);
        boolean faceEast = Math.abs(eye[0] - (targetCell[0] + 1)) < Math.abs(eye[0] - targetCell[0]);

        double bx = targetCell[0], by = targetCell[1], bz = targetCell[2];
        double jit = GRID_STEP * 0.1;

        ArrayList<RotationCandidate> cands = new ArrayList<RotationCandidate>((GRID_N + 1) * (GRID_N + 1) * 3 + 1);
        cands.add(new RotationCandidate(0, baseYaw, basePitch));

        for (int row = 0; row <= GRID_N; row++) {
            double v = clamp01(row * GRID_STEP + jitter(jit));
            for (int col = 0; col <= GRID_N; col++) {
                double u = clamp01(col * GRID_STEP + jitter(jit));

                float[] rY = rotationsFromEye(eye, bx + u, faceUp ? by + 1 - GRID_INSET : by + GRID_INSET, bz + v);
                cands.add(new RotationCandidate(
                        Math.abs(wrap(rY[0] - baseYaw)) + Math.abs(rY[1] - basePitch), rY[0], rY[1]));

                float[] rZ = rotationsFromEye(eye, bx + u, by + v, faceSouth ? bz + 1 - GRID_INSET : bz + GRID_INSET);
                cands.add(new RotationCandidate(
                        Math.abs(wrap(rZ[0] - baseYaw)) + Math.abs(rZ[1] - basePitch), rZ[0], rZ[1]));

                float[] rX = rotationsFromEye(eye, faceEast ? bx + 1 - GRID_INSET : bx + GRID_INSET, by + v, bz + u);
                cands.add(new RotationCandidate(
                        Math.abs(wrap(rX[0] - baseYaw)) + Math.abs(rX[1] - basePitch), rX[0], rX[1]));
            }
        }

        Collections.sort(cands, new Comparator<RotationCandidate>() {
            @Override
            public int compare(RotationCandidate a, RotationCandidate b) {
                return Double.compare(a.cost, b.cost);
            }
        });

        for (RotationCandidate c : cands) {
            Object mop = rayBlock(reachVal, c.yaw, c.pitch);
            if (mop == null) continue;
            int[] hitBlock = mopIntPos(mop);
            Object face = MC.mopSide(mop);
            if (hitBlock == null || face == null) continue;
            if (samePos(hitBlock, targetCell) && hitBlock[1] >= minY
                    && !(isDown(face) && targetCell[1] == minY)
                    && canPlaceOnSide(held, MC.newPos(hitBlock[0], hitBlock[1], hitBlock[2]), face)) {
                int[] placementPos = offsetPos(hitBlock, face);
                if (isNearBed(placementPos)) {
                    continue;
                }
                return new AimResult(hitBlock, face, c.yaw, c.pitch);
            }
        }
        return null;
    }

    private AimResult sidesAim() {
        Object p = MC.player();
        Object w = MC.world();
        if (p == null || w == null) return null;
        int[] feet = {(int) Math.floor(MC.posX(p)), (int) Math.floor(MC.posY(p)), (int) Math.floor(MC.posZ(p))};
        int[] head = {feet[0], feet[1] + 1, feet[2]};
        double r = REACH;
        double[] eye = MC.vecXYZ(MC.positionEyes(p, 1.0f));
        if (eye == null) return null;

        ArrayList<int[]> baseline = new ArrayList<int[]>(8);
        for (String dir : HORIZONTALS) {
            Object f = MC.facing(dir);
            baseline.add(offsetPos(feet, f));
            baseline.add(offsetPos(head, f));
        }

        ArrayList<int[]> primaryGoals = new ArrayList<int[]>(baseline.size());
        for (int[] pos : baseline) {
            if (!MC.blockReplaceable(w, MC.newPos(pos[0], pos[1], pos[2]))) continue;
            if (!hasReplaceableNeighbor(pos, feet, head)) continue;
            primaryGoals.add(pos);
        }
        if (primaryGoals.isEmpty()) return null;

        double[] enemyPos = nearestEnemyPos();
        if (enemyPos != null) {
            final double ex = enemyPos[0], ey = enemyPos[1], ez = enemyPos[2];
            Collections.sort(baseline, new Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return Double.compare(sq(a[0] + 0.5 - ex) + sq(a[1] + 0.5 - ey) + sq(a[2] + 0.5 - ez),
                            sq(b[0] + 0.5 - ex) + sq(b[1] + 0.5 - ey) + sq(b[2] + 0.5 - ez));
                }
            });
            int picked = 0;
            for (int i = 0; i < baseline.size() && picked < 3; i++) {
                int[] pos = baseline.get(i);
                if (!MC.blockReplaceable(w, MC.newPos(pos[0], pos[1], pos[2]))) continue;
                if (!hasReplaceableNeighbor(pos, feet, head)) continue;
                AimResult rEnemy = findBestForGoals(singleton(pos), r, eye);
                if (rEnemy != null) return rEnemy;
                picked++;
            }
        }

        AimResult result = findBestForGoals(primaryGoals, r, eye);
        if (result != null) return result;

        ArrayList<int[]> frontier = new ArrayList<int[]>(primaryGoals);
        HashSet<Long> seen = new HashSet<Long>(frontier.size() * 8);
        for (int[] g : frontier) seen.add(posLong(g));
        Object[] allFaces = allFacings();

        for (int iter = 0; iter < 5; iter++) {
            if (frontier.isEmpty()) break;
            ArrayList<int[]> layer = new ArrayList<int[]>(frontier.size() * 3);
            for (int[] g : frontier) {
                for (Object f : allFaces) {
                    int[] s = offsetPos(g, f);
                    if (!MC.blockReplaceable(w, MC.newPos(s[0], s[1], s[2]))) continue;
                    if (!seen.add(Long.valueOf(posLong(s)))) continue;
                    layer.add(s);
                }
            }
            if (!layer.isEmpty()) {
                AimResult rLayer = findBestForGoals(layer, r, eye);
                if (rLayer != null) return rLayer;
            }
            frontier = layer;
        }
        return null;
    }

    private boolean hasReplaceableNeighbor(int[] pos, int[]... exclude) {
        Object w = MC.world();
        for (Object f : allFacings()) {
            int[] neighbor = offsetPos(pos, f);
            if (!MC.blockReplaceable(w, MC.newPos(neighbor[0], neighbor[1], neighbor[2]))) continue;
            boolean excluded = false;
            for (int[] ex : exclude) {
                if (samePos(neighbor, ex)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) return true;
        }
        return false;
    }

    private AimResult findBestForGoals(List<int[]> goals, double reachVal, double[] eye) {
        if (goals == null || goals.isEmpty()) return null;
        if (plannedSlot < 0 || plannedSlot > 8) return null;
        Object p = MC.player();
        Object w = MC.world();
        if (p == null || w == null) return null;

        Object held = MC.getStack(MC.inventory(p), plannedSlot);
        float curYaw = MC.yaw(p);
        float curPitch = MC.pitch(p);

        Object now = rayBlock(reachVal, curYaw, curPitch);
        if (now != null) {
            int[] support = mopIntPos(now);
            Object faceHit = MC.mopSide(now);
            if (support != null && faceHit != null
                    && !MC.blockReplaceable(w, MC.newPos(support[0], support[1], support[2]))
                    && canPlaceOnSide(held, MC.newPos(support[0], support[1], support[2]), faceHit)) {
                for (int[] goal : goals) {
                    AimResult ok = tryPlacement(reachVal, curYaw, curPitch, support, faceHit, goal);
                    if (ok != null) return ok;
                }
            }
        }

        double jit = GRID_STEP * 0.1;
        double insetTop = 1 - GRID_INSET - 1e-3;
        double insetBot = GRID_INSET + 1e-3;

        ArrayList<PlacementCandidate> cands = new ArrayList<PlacementCandidate>(
                Math.max(16, goals.size() * 6 * (GRID_N + 1) * (GRID_N + 1)));

        for (int[] g : goals) {
            for (int s = 0; s < SUPPORT_DELTAS.length; s++) {
                int[] d = SUPPORT_DELTAS[s];
                int[] support = {g[0] + d[0], g[1] + d[1], g[2] + d[2]};
                Object face = MC.facing(SUPPORT_FACES[s]);
                if (face == null) continue;
                if (MC.blockReplaceable(w, MC.newPos(support[0], support[1], support[2]))) continue;
                if (!canPlaceOnSide(held, MC.newPos(support[0], support[1], support[2]), face)) continue;

                double sx = support[0], sy = support[1], sz = support[2];
                for (int row = 0; row <= GRID_N; row++) {
                    boolean ltr = (row & 1) == 0;
                    double v = clamp01(row * GRID_STEP + jitter(jit));
                    for (int col = 0; col <= GRID_N; col++) {
                        double cu = clamp01(col * GRID_STEP + jitter(jit));
                        double u = ltr ? cu : 1.0 - cu;

                        double pxx, pyy, pzz;
                        if (d[1] != 0) {
                            pxx = sx + u; pzz = sz + v;
                            pyy = sy + (d[1] < 0 ? insetTop : insetBot);
                        } else if (d[2] != 0) {
                            pxx = sx + u; pyy = sy + v;
                            pzz = sz + (d[2] < 0 ? insetTop : insetBot);
                        } else {
                            pzz = sz + u; pyy = sy + v;
                            pxx = sx + (d[0] < 0 ? insetTop : insetBot);
                        }

                        float[] rot = rotationsFromEye(eye, pxx, pyy, pzz);
                        float dYaw = Math.abs(wrap(rot[0] - curYaw));
                        float dPit = Math.abs(rot[1] - curPitch);
                        if (dYaw < 0.1f && dPit < 0.1f) continue;
                        cands.add(new PlacementCandidate(dYaw + dPit, rot[0], rot[1], support, face, g));
                    }
                }
            }
        }

        if (cands.isEmpty()) return null;
        Collections.sort(cands, new Comparator<PlacementCandidate>() {
            @Override
            public int compare(PlacementCandidate a, PlacementCandidate b) {
                return Double.compare(a.cost, b.cost);
            }
        });

        for (PlacementCandidate c : cands) {
            AimResult ok = tryPlacement(reachVal, c.yaw, c.pitch, c.support, c.face, c.goal);
            if (ok != null) return ok;
        }
        return null;
    }

    private AimResult tryPlacement(double reachVal, float yaw, float pit,
                                   int[] expectedSupport, Object expectedFace, int[] goal) {
        Object mop = rayBlock(reachVal, yaw, pit);
        if (mop == null) return null;
        int[] hitBlock = mopIntPos(mop);
        Object faceHit = MC.mopSide(mop);
        if (hitBlock == null || faceHit == null) return null;
        if (!samePos(hitBlock, expectedSupport)) return null;
        if (!faceHit.equals(expectedFace)) return null;
        int[] placed = offsetPos(hitBlock, faceHit);
        if (!samePos(placed, goal)) return null;
        if (isNearBed(placed)) return null;
        return new AimResult(hitBlock, faceHit, yaw, pit);
    }

    private boolean isDirectAdjacentPlacement(int[] p) {
        Object pl = MC.player();
        if (pl == null) return false;
        int fx = (int) Math.floor(MC.posX(pl));
        int fy = (int) Math.floor(MC.posY(pl));
        int fz = (int) Math.floor(MC.posZ(pl));
        int dx = p[0] - fx, dy = p[1] - fy, dz = p[2] - fz;
        if (dx == 0 && dz == 0 && dy == 2) return true;
        return (dy == 0 || dy == 1)
                && ((Math.abs(dx) == 1 && dz == 0) || (Math.abs(dz) == 1 && dx == 0));
    }

    private boolean isNearBed(int[] placementPos) {
        if (!skipNearBed.isToggled()) return false;
        Object w = MC.world();
        if (w == null || placementPos == null) return false;
        Object cell = MC.newPos(placementPos[0], placementPos[1], placementPos[2]);
        if (MC.isBlockInstance(MC.blockOf(MC.blockState(w, cell)), "BlockBed")) {
            return true;
        }
        for (Object f : allFacings()) {
            int[] n = offsetPos(placementPos, f);
            Object nc = MC.newPos(n[0], n[1], n[2]);
            if (MC.isBlockInstance(MC.blockOf(MC.blockState(w, nc)), "BlockBed")) {
                return true;
            }
        }
        return false;
    }

    // ---------- small helpers ----------

    private Object rayBlock(double reach, float yaw, float pitch) {
        try {
            Object p = MC.player();
            Object w = MC.world();
            if (p == null || w == null) return null;
            Object eyes = MC.positionEyes(p, 1.0f);
            double[] e = MC.vecXYZ(eyes);
            if (e == null) return null;
            double f = Math.cos(-yaw * 0.017453292 - Math.PI);
            double f1 = Math.sin(-yaw * 0.017453292 - Math.PI);
            double f2 = -Math.cos(-pitch * 0.017453292);
            double f3 = Math.sin(-pitch * 0.017453292);
            Object end = MC.newVec(e[0] + f1 * f2 * reach, e[1] + f3 * reach, e[2] + f * f2 * reach);
            Object mop = MC.rayTrace(w, eyes, end);
            if (mop == null || !"BLOCK".equals(MC.mopType(mop))) return null;
            return mop;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static float[] rotationsFromEye(double[] eye, double tx, double ty, double tz) {
        double dx = tx - eye[0];
        double dy = ty - eye[1];
        double dz = tz - eye[2];
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
        return new float[]{yaw, pitch};
    }

    private static float wrap(float v) {
        v %= 360.0f;
        if (v >= 180.0f) v -= 360.0f;
        if (v < -180.0f) v += 360.0f;
        return v;
    }

    private static int[] mopIntPos(Object mop) {
        Object pos = MC.mopPos(mop);
        return pos == null ? null : MC.posXYZ(pos);
    }

    private static boolean samePos(int[] a, int[] b) {
        return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static int[] offsetPos(int[] p, Object face) {
        int[] d = faceDelta(face);
        return new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]};
    }

    private static int[] faceDelta(Object face) {
        String n = String.valueOf(face);
        if ("DOWN".equals(n)) return new int[]{0, -1, 0};
        if ("UP".equals(n)) return new int[]{0, 1, 0};
        if ("NORTH".equals(n)) return new int[]{0, 0, -1};
        if ("SOUTH".equals(n)) return new int[]{0, 0, 1};
        if ("WEST".equals(n)) return new int[]{-1, 0, 0};
        if ("EAST".equals(n)) return new int[]{1, 0, 0};
        return new int[]{0, 0, 0};
    }

    private static boolean isDown(Object face) {
        return "DOWN".equals(String.valueOf(face));
    }

    private static Object[] allFacings() {
        try {
            Object r = MC.callStatic(MC.cls("net.minecraft.util.EnumFacing"), "values", new Class<?>[0]);
            if (r instanceof Object[]) return (Object[]) r;
        } catch (Throwable ignored) {
        }
        return new Object[0];
    }

    private static List<int[]> singleton(int[] pos) {
        ArrayList<int[]> l = new ArrayList<int[]>(1);
        l.add(pos);
        return l;
    }

    private static long posLong(int[] p) {
        return (((long) p[0] & 0x3FFFFFFL) << 38) | (((long) p[1] & 0xFFFL) << 26) | (((long) p[2]) & 0x3FFFFFFL);
    }

    private double dist2Point(double[] eye, int bx, int by, int bz) {
        double cx = Math.max(bx, Math.min(bx + 1, eye[0]));
        double cy = Math.max(by, Math.min(by + 1, eye[1]));
        double cz = Math.max(bz, Math.min(bz + 1, eye[2]));
        double dx = eye[0] - cx, dy = eye[1] - cy, dz = eye[2] - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private double[] nearestEnemyPos() {
        try {
            Object self = MC.player();
            if (self == null) return null;
            double sx = MC.posX(self), sy = MC.posY(self), sz = MC.posZ(self);
            double best = 100 * 100;
            double[] out = null;
            for (Object pl : MC.players(MC.world())) {
                if (pl == null || pl == self) continue;
                double dx = MC.posX(pl) - sx, dy = MC.posY(pl) - sy, dz = MC.posZ(pl) - sz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best) {
                    best = d;
                    out = new double[]{MC.posX(pl), MC.posY(pl), MC.posZ(pl)};
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean canPlaceOnSide(Object held, Object supportPos, Object face) {
        try {
            if (held == null) return false;
            Object item = MC.stackItem(held);
            return Boolean.TRUE.equals(MC.call(item, "canPlaceBlockOnSide",
                    new Class<?>[]{MC.cls("net.minecraft.world.World"),
                            MC.cls("net.minecraft.util.BlockPos"),
                            MC.cls("net.minecraft.util.EnumFacing"),
                            MC.cls("net.minecraft.entity.player.EntityPlayer"),
                            MC.cls("net.minecraft.item.ItemStack")},
                    MC.world(), supportPos, face, MC.player(), held));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double jitter(double range) {
        return range > 0 ? (Math.random() * 2 - 1) * range : 0;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static class BlockCandidate {
        final double dist;
        final int[] pos;
        BlockCandidate(double dist, int[] pos) {
            this.dist = dist;
            this.pos = pos;
        }
    }

    private static class RotationCandidate {
        final double cost;
        final float yaw, pitch;
        RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static class PlacementCandidate {
        final double cost;
        final float yaw, pitch;
        final int[] support, goal;
        final Object face;
        PlacementCandidate(double cost, float yaw, float pitch, int[] support, Object face, int[] goal) {
            this.cost = cost;
            this.yaw = yaw;
            this.pitch = pitch;
            this.support = support;
            this.face = face;
            this.goal = goal;
        }
    }

    private static class AimResult {
        final int[] supportBlock;
        final Object face;
        final float yaw, pitch;
        AimResult(int[] supportBlock, Object face, float yaw, float pitch) {
            this.supportBlock = supportBlock;
            this.face = face;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
