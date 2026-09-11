package com.rottenapple.bridge;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-only game access for ported Raven modules (MCP 1.8.9 names).
 * Every call is defensive: unknown mappings yield safe defaults, never throws.
 */
public final class MC {
    private MC() {
    }

    private static final Map<String, Class<?>> CLASSES = new HashMap<String, Class<?>>();
    private static final Map<String, Field> FIELDS = new HashMap<String, Field>();
    private static final Map<String, Method> METHODS = new HashMap<String, Method>();

    public static Class<?> cls(String name) {
        Class<?> c = CLASSES.get(name);
        if (c != null || CLASSES.containsKey(name)) {
            return c;
        }
        c = GameBridge.loadGameClass(name);
        CLASSES.put(name, c);
        return c;
    }

    public static Field fld(Class<?> c, String name) {
        if (c == null) return null;
        String k = c.getName() + "#" + name;
        if (FIELDS.containsKey(k)) return FIELDS.get(k);
        Field f = null;
        try {
            f = c.getField(name);
        } catch (Throwable ignored) {
        }
        if (f == null) {
            try {
                f = c.getDeclaredField(name);
                f.setAccessible(true);
            } catch (Throwable ignored) {
                f = null;
            }
        }
        FIELDS.put(k, f);
        return f;
    }

    public static Method mth(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name).append('(');
        for (Class<?> p : params) sb.append(p.getName()).append(',');
        String k = sb.toString();
        if (METHODS.containsKey(k)) return METHODS.get(k);
        Method m = null;
        try {
            m = c.getMethod(name, params);
        } catch (Throwable ignored) {
        }
        if (m == null) {
            // Fallback: game classes sometimes declare narrower types
            // (e.g. EntityPlayerSP instead of EntityPlayer). Match by arity.
            try {
                for (Method cand : c.getMethods()) {
                    if (cand.getName().equals(name)
                            && cand.getParameterTypes().length == params.length) {
                        m = cand;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        METHODS.put(k, m);
        return m;
    }

    public static Object get(Object o, String field) {
        try {
            if (o == null) return null;
            Field f = fld(o.getClass(), field);
            return f == null ? null : f.get(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean set(Object o, String field, Object v) {
        try {
            if (o == null) return false;
            Field f = fld(o.getClass(), field);
            if (f == null) return false;
            f.set(o, v);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object call(Object o, String method, Class<?>[] types, Object... args) {
        try {
            if (o == null) return null;
            Method m = mth(o.getClass(), method, types);
            return m == null ? null : m.invoke(o, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object callStatic(Class<?> c, String method, Class<?>[] types, Object... args) {
        try {
            if (c == null) return null;
            Method m = mth(c, method, types);
            return m == null ? null : m.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object staticField(Class<?> c, String name) {
        try {
            if (c == null) return null;
            Field f = fld(c, name);
            return f == null ? null : f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- root objects ----------

    public static Object mc() {
        return GameBridge.getMinecraftInstance();
    }

    public static Object player() {
        return get(mc(), "thePlayer");
    }

    public static Object world() {
        return get(mc(), "theWorld");
    }

    public static Object settings() {
        return get(mc(), "gameSettings");
    }

    public static Object controller() {
        return get(mc(), "playerController");
    }

    public static Object screen() {
        return get(mc(), "currentScreen");
    }

    public static boolean nullCheck() {
        return player() != null && world() != null;
    }

    public static boolean inGameFocus() {
        try {
            Object f = get(mc(), "inGameHasFocus");
            return Boolean.TRUE.equals(f);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------- KeyBinding ----------

    public static Object keyBind(String field) {
        return get(settings(), field);
    }

    public static int keyCode(Object kb) {
        try {
            Object r = call(kb, "getKeyCode", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean kbDown(Object kb) {
        try {
            Object r = call(kb, "isKeyDown", new Class<?>[0]);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setKeyState(int code, boolean down) {
        try {
            Class<?> kb = cls("net.minecraft.client.settings.KeyBinding");
            callStatic(kb, "setKeyBindState", new Class<?>[]{Integer.TYPE, Boolean.TYPE},
                    Integer.valueOf(code), Boolean.valueOf(down));
        } catch (Throwable ignored) {
        }
    }

    public static void onTickKey(int code) {
        try {
            Class<?> kb = cls("net.minecraft.client.settings.KeyBinding");
            callStatic(kb, "onTick", new Class<?>[]{Integer.TYPE}, Integer.valueOf(code));
        } catch (Throwable ignored) {
        }
    }

    /** Mirrors Utils.isBindDown (attack key may be mouse-bound, code < 0). */
    public static boolean isBindDown(Object kb) {
        try {
            int code = keyCode(kb);
            if (code < 0) return mouseDown(code + 100);
            Class<?> k = cls("org.lwjgl.input.Keyboard");
            Object r = callStatic(k, "isKeyDown", new Class<?>[]{Integer.TYPE}, Integer.valueOf(code));
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------- LWJGL input ----------

    public static boolean keyDown(int lwjglCode) {
        try {
            Class<?> k = cls("org.lwjgl.input.Keyboard");
            Object r = callStatic(k, "isKeyDown", new Class<?>[]{Integer.TYPE}, Integer.valueOf(lwjglCode));
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean mouseDown(int button) {
        try {
            Class<?> m = cls("org.lwjgl.input.Mouse");
            Object r = callStatic(m, "isButtonDown", new Class<?>[]{Integer.TYPE}, Integer.valueOf(button));
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Pokes LWJGL's internal button state (mirrors Raven's ReflectionUtils.setButton, minus Forge event). */
    public static void pokeMouseButton(int button, boolean down) {
        try {
            Class<?> m = cls("org.lwjgl.input.Mouse");
            if (m == null) return;
            Field f = fld(m, "buttons");
            if (f == null) return;
            Object o = f.get(null);
            if (o instanceof ByteBuffer) {
                ((ByteBuffer) o).put(button, (byte) (down ? 1 : 0));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------- entity scalars ----------

    public static double d(Object o, String field) {
        try {
            Object v = get(o, field);
            return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    public static float f(Object o, String field) {
        try {
            Object v = get(o, field);
            return v instanceof Number ? ((Number) v).floatValue() : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static int i(Object o, String field) {
        try {
            Object v = get(o, field);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean b(Object o, String field) {
        try {
            return Boolean.TRUE.equals(get(o, field));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static double motionX(Object e) {
        return d(e, "motionX");
    }

    public static double motionY(Object e) {
        return d(e, "motionY");
    }

    public static double motionZ(Object e) {
        return d(e, "motionZ");
    }

    public static void setMotion(Object e, double x, double y, double z) {
        set(e, "motionX", Double.valueOf(x));
        set(e, "motionY", Double.valueOf(y));
        set(e, "motionZ", Double.valueOf(z));
    }

    public static int hurtTime(Object e) {
        return i(e, "hurtTime");
    }

    public static int maxHurtTime(Object e) {
        return i(e, "maxHurtTime");
    }

    public static boolean onGround(Object e) {
        return b(e, "onGround");
    }

    public static boolean isDead(Object e) {
        return b(e, "isDead");
    }

    public static int deathTime(Object e) {
        return i(e, "deathTime");
    }

    public static int ticksExisted(Object e) {
        return i(e, "ticksExisted");
    }

    public static float fallDistance(Object e) {
        return f(e, "fallDistance");
    }

    public static float yaw(Object e) {
        return f(e, "rotationYaw");
    }

    public static float pitch(Object e) {
        return f(e, "rotationPitch");
    }

    public static double posX(Object e) {
        return d(e, "posX");
    }

    public static double posY(Object e) {
        return d(e, "posY");
    }

    public static double posZ(Object e) {
        return d(e, "posZ");
    }

    public static float height(Object e) {
        return f(e, "height");
    }

    public static Object capabilities(Object p) {
        return get(p, "capabilities");
    }

    public static boolean cap(Object p, String name) {
        return b(capabilities(p), name);
    }

    public static Object movementInput(Object p) {
        return get(p, "movementInput");
    }

    public static float miForward(Object mi) {
        return f(mi, "moveForward");
    }

    public static float miStrafe(Object mi) {
        return f(mi, "moveStrafe");
    }

    public static void setMiSneak(Object mi, boolean v) {
        set(mi, "sneak", Boolean.valueOf(v));
    }

    public static Object inventory(Object p) {
        return get(p, "inventory");
    }

    public static int currentItem(Object inv) {
        return i(inv, "currentItem");
    }

    public static void setCurrentItem(Object inv, int slot) {
        set(inv, "currentItem", Integer.valueOf(slot));
    }

    public static Object getStack(Object inv, int slot) {
        try {
            return call(inv, "getStackInSlot", new Class<?>[]{Integer.TYPE}, Integer.valueOf(slot));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object[] mainInventory(Object inv) {
        try {
            Object arr = get(inv, "mainInventory");
            if (arr == null || !arr.getClass().isArray()) return new Object[0];
            int n = Array.getLength(arr);
            Object[] out = new Object[n];
            for (int k = 0; k < n; k++) out[k] = Array.get(arr, k);
            return out;
        } catch (Throwable ignored) {
            return new Object[0];
        }
    }

    public static Object getHeldItem(Object p) {
        try {
            return call(p, "getHeldItem", new Class<?>[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isUsingItem(Object p) {
        try {
            return Boolean.TRUE.equals(call(p, "isUsingItem", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBlocking(Object p) {
        try {
            return Boolean.TRUE.equals(call(p, "isBlocking", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isBurning(Object p) {
        try {
            return Boolean.TRUE.equals(call(p, "isBurning", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isInvisible(Object e) {
        try {
            return Boolean.TRUE.equals(call(e, "isInvisible", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPlayerSleeping(Object p) {
        try {
            return Boolean.TRUE.equals(call(p, "isPlayerSleeping", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float getHealth(Object e) {
        try {
            Object r = call(e, "getHealth", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 20.0f;
        } catch (Throwable ignored) {
            return 20.0f;
        }
    }

    public static float getMaxHealth(Object e) {
        try {
            Object r = call(e, "getMaxHealth", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 20.0f;
        } catch (Throwable ignored) {
            return 20.0f;
        }
    }

    public static float getAbsorption(Object e) {
        try {
            Object r = call(e, "getAbsorptionAmount", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static int foodLevel(Object p) {
        try {
            Object fs = call(p, "getFoodStats", new Class<?>[0]);
            Object r = call(fs, "getFoodLevel", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).intValue() : 20;
        } catch (Throwable ignored) {
            return 20;
        }
    }

    public static Object potion(String name) {
        return staticField(cls("net.minecraft.potion.Potion"), name);
    }

    public static Object activePotion(Object p, Object potion) {
        try {
            if (potion == null) return null;
            Class<?> pc = cls("net.minecraft.potion.Potion");
            return call(p, "getActivePotionEffect", new Class<?>[]{pc}, potion);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isPotionActive(Object p, Object potion) {
        try {
            if (potion == null) return false;
            Class<?> pc = cls("net.minecraft.potion.Potion");
            return Boolean.TRUE.equals(call(p, "isPotionActive", new Class<?>[]{pc}, potion));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object getEquipment(Object p, int slot) {
        try {
            return call(p, "getEquipmentInSlot", new Class<?>[]{Integer.TYPE}, Integer.valueOf(slot));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String entName(Object e) {
        try {
            Object r = call(e, "getName", new Class<?>[0]);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String displayNameFmt(Object e) {
        try {
            Object comp = call(e, "getDisplayName", new Class<?>[0]);
            Object r = call(comp, "getFormattedText", new Class<?>[0]);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String displayNameUnf(Object e) {
        try {
            Object comp = call(e, "getDisplayName", new Class<?>[0]);
            Object r = call(comp, "getUnformattedText", new Class<?>[0]);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static float eyeHeight(Object e) {
        try {
            Object r = call(e, "getEyeHeight", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 1.62f;
        } catch (Throwable ignored) {
            return 1.62f;
        }
    }

    public static Object positionEyes(Object e, float partial) {
        try {
            return call(e, "getPositionEyes", new Class<?>[]{Float.TYPE}, Float.valueOf(partial));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static float getDistance(Object e, double x, double y, double z) {
        try {
            Class<?> ec = e.getClass();
            Method m = null;
            try {
                m = ec.getMethod("getDistance", Double.TYPE, Double.TYPE, Double.TYPE);
            } catch (Throwable ignored) {
            }
            if (m == null) return Float.MAX_VALUE;
            Object r = m.invoke(e, Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
            return r instanceof Number ? ((Number) r).floatValue() : Float.MAX_VALUE;
        } catch (Throwable ignored) {
            return Float.MAX_VALUE;
        }
    }

    public static boolean canCollide(Object e) {
        try {
            return Boolean.TRUE.equals(call(e, "canBeCollidedWith", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static float collisionBorder(Object e) {
        try {
            Object r = call(e, "getCollisionBorderSize", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static Object entityBox(Object e) {
        try {
            return call(e, "getEntityBoundingBox", new Class<?>[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** minX,minY,minZ,maxX,maxY,maxZ or null. */
    public static double[] boxCoords(Object box) {
        try {
            if (box == null) return null;
            return new double[]{d(box, "minX"), d(box, "minY"), d(box, "minZ"),
                    d(box, "maxX"), d(box, "maxY"), d(box, "maxZ")};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object offsetBox(Object box, double x, double y, double z) {
        try {
            Class<?> bc = box.getClass();
            return call(box, "offset", new Class<?>[]{Double.TYPE, Double.TYPE, Double.TYPE},
                    Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------- world ----------

    @SuppressWarnings("unchecked")
    public static List<Object> players(Object world) {
        try {
            Object o = get(world, "playerEntities");
            if (o instanceof List) {
                return new ArrayList<Object>((List<Object>) o);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<Object>();
    }

    public static Object blockState(Object world, Object pos) {
        try {
            Class<?> pc = cls("net.minecraft.util.BlockPos");
            return call(world, "getBlockState", new Class<?>[]{pc}, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object blockOf(Object state) {
        try {
            return call(state, "getBlock", new Class<?>[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> colliding(Object world, Object entity, Object box) {
        try {
            Class<?> ec = cls("net.minecraft.entity.Entity");
            Class<?> bc = box == null ? null : box.getClass();
            if (ec == null || bc == null) return new ArrayList<Object>();
            Object r = call(world, "getCollidingBoundingBoxes", new Class<?>[]{ec, bc}, entity, box);
            if (r instanceof List) {
                return new ArrayList<Object>((List<Object>) r);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<Object>();
    }

    public static Object rayTrace(Object world, Object v1, Object v2) {
        return rayTrace(world, v1, v2, false, false, false);
    }

    public static Object rayTrace(Object world, Object v1, Object v2, boolean b1, boolean b2, boolean b3) {
        try {
            Class<?> vc = v1 == null ? null : v1.getClass();
            if (vc == null) return null;
            return call(world, "rayTraceBlocks",
                    new Class<?>[]{vc, vc, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE},
                    v1, v2, Boolean.valueOf(b1), Boolean.valueOf(b2), Boolean.valueOf(b3));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Block ray with entity-in-front suppression (mirrors Raven's
     * RotationUtils.rayTraceBlockIfNoEntityInFront). Returns the BlockPos or null.
     */
    public static Object blockHoverPos(double reach, float yaw, float pitch) {
        try {
            Object p = player();
            Object w = world();
            if (p == null || w == null) return null;
            Object eyes = positionEyes(p, 1.0f);
            double[] e = vecXYZ(eyes);
            if (e == null) return null;
            double f = Math.cos(-yaw * 0.017453292 - Math.PI);
            double f1 = Math.sin(-yaw * 0.017453292 - Math.PI);
            double f2 = -Math.cos(-pitch * 0.017453292);
            double f3 = Math.sin(-pitch * 0.017453292);
            double lx = f1 * f2, ly = f3, lz = f * f2;
            Object end = newVec(e[0] + lx * reach, e[1] + ly * reach, e[2] + lz * reach);
            Object blockHit = rayTrace(w, eyes, end, false, false, true);
            if (blockHit == null || !"BLOCK".equals(mopType(blockHit))) return null;
            double[] h = vecXYZ(mopHitVec(blockHit));
            if (h == null) return null;
            double blockDist = Math.sqrt(sq(e[0] - h[0]) + sq(e[1] - h[1]) + sq(e[2] - h[2]));
            Object sweep = expandBox(addCoordBox(entityBox(p), lx * reach, ly * reach, lz * reach), 1.0, 1.0, 1.0);
            for (Object entity : entitiesExcluding(w, p, sweep)) {
                try {
                    if (!canCollide(entity)) continue;
                    float border = collisionBorder(entity);
                    Object aabb = expandBox(entityBox(entity), border, border, border);
                    Object entityHit = boxIntercept(aabb, eyes, end);
                    if (boxContains(aabb, eyes)) return null;
                    if (entityHit != null) {
                        double[] hv = vecXYZ(mopHitVec(entityHit));
                        if (hv == null) continue;
                        double ed = Math.sqrt(sq(e[0] - hv[0]) + sq(e[1] - hv[1]) + sq(e[2] - hv[2]));
                        if (ed < blockDist) return null;
                    }
                } catch (Throwable ignored) {
                }
            }
            return mopPos(blockHit);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // ---------- blocks / positions ----------

    public static Object newPos(double x, double y, double z) {
        try {
            Class<?> pc = cls("net.minecraft.util.BlockPos");
            if (pc == null) return null;
            return pc.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE)
                    .newInstance(Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int[] posXYZ(Object pos) {
        try {
            Class<?> pc = cls("net.minecraft.util.BlockPos");
            int x = ((Number) call(pos, "getX", new Class<?>[0])).intValue();
            int y = ((Number) call(pos, "getY", new Class<?>[0])).intValue();
            int z = ((Number) call(pos, "getZ", new Class<?>[0])).intValue();
            return new int[]{x, y, z};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object posOffset(Object pos, Object facing) {
        try {
            Class<?> fc = cls("net.minecraft.util.EnumFacing");
            return call(pos, "offset", new Class<?>[]{fc}, facing);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object facing(String name) {
        return staticField(cls("net.minecraft.util.EnumFacing"), name);
    }

    public static String blockRegId(Object block) {
        try {
            Class<?> bc = cls("net.minecraft.block.Block");
            Object reg = staticField(bc, "blockRegistry");
            Object rl = call(reg, "getNameForObject", new Class<?>[]{Object.class}, block);
            return rl == null ? null : String.valueOf(rl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int blockMeta(Object block, Object state) {
        try {
            Object r = call(block, "getMetaFromState", new Class<?>[]{state.getClass()}, state);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable ignored) {
            try {
                Class<?> sc = cls("net.minecraft.block.state.IBlockState");
                Object r = call(block, "getMetaFromState", new Class<?>[]{sc}, state);
                return r instanceof Number ? ((Number) r).intValue() : 0;
            } catch (Throwable ignored2) {
                return 0;
            }
        }
    }

    public static String blockStorageId(Object block, Object state) {
        try {
            String reg = blockRegId(block);
            if (reg == null) return null;
            int meta = blockMeta(block, state);
            return meta != 0 ? reg + ":" + meta : reg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object blocksField(String name) {
        return staticField(cls("net.minecraft.init.Blocks"), name);
    }

    public static Class<?> blockClass(String simple) {
        return cls("net.minecraft.block." + simple);
    }

    public static boolean isBlockInstance(Object block, String simple) {
        try {
            Class<?> c = blockClass(simple);
            return block != null && c != null && c.isInstance(block);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean blockReplaceable(Object world, Object pos) {
        try {
            Object state = blockState(world, pos);
            Object block = blockOf(state);
            if (block == null) return true;
            Class<?> wc = world.getClass();
            Class<?> pc = cls("net.minecraft.util.BlockPos");
            Object r = call(block, "isReplaceable", new Class<?>[]{wc.getSuperclass() != null ? findWorldClass() : wc, pc}, world, pos);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Class<?> findWorldClass() {
        Class<?> c = cls("net.minecraft.world.World");
        return c == null ? Object.class : c;
    }

    // ---------- items ----------

    public static Class<?> itemClass(String simple) {
        return cls("net.minecraft.item." + simple);
    }

    public static boolean isItemInstance(Object item, String simple) {
        try {
            Class<?> c = itemClass(simple);
            return item != null && c != null && c.isInstance(item);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object itemsField(String name) {
        return staticField(cls("net.minecraft.init.Items"), name);
    }

    public static String itemRegId(Object item) {
        try {
            Class<?> ic = cls("net.minecraft.item.Item");
            Object reg = staticField(ic, "itemRegistry");
            Object rl = call(reg, "getNameForObject", new Class<?>[]{Object.class}, item);
            return rl == null ? null : String.valueOf(rl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int stackMeta(Object stack) {
        try {
            Object r = call(stack, "getItemDamage", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static String stackStorageId(Object stack) {
        try {
            if (stack == null) return null;
            Object item = call(stack, "getItem", new Class<?>[0]);
            String reg = itemRegId(item);
            if (reg == null) return null;
            int meta = stackMeta(stack);
            return meta != 0 ? reg + ":" + meta : reg;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Mirrors ItemSearchIndex.matches (registry + :* wildcard). */
    public static boolean matchItemList(List<String> ids, Object stack) {
        try {
            if (ids == null || ids.isEmpty() || stack == null) return false;
            String mine = stackStorageId(stack);
            if (mine == null) return false;
            for (String id : ids) {
                if (id == null) continue;
                if (id.equals(mine)) return true;
                String base = id.endsWith(":*") ? id.substring(0, id.length() - 2) : null;
                if (base == null) {
                    String[] p = mine.split(":");
                    if (p.length >= 2) {
                        String mreg = p[0] + ":" + p[1];
                        if (id.equals(mreg + ":*")) return true;
                    }
                    continue;
                }
                String[] p = mine.split(":");
                if (p.length >= 2 && (p[0] + ":" + p[1]).equals(base)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static Object stackItem(Object stack) {
        try {
            return call(stack, "getItem", new Class<?>[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int stackSize(Object stack) {
        return i(stack, "stackSize");
    }

    public static float strVsBlock(Object stack, Object block) {
        try {
            Class<?> bc = block == null ? null : block.getClass();
            Object r = call(stack, "getStrVsBlock", new Class<?>[]{findBlockClass()}, stack == null ? null : block);
            return r instanceof Number ? ((Number) r).floatValue() : 1.0f;
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static Class<?> findBlockClass() {
        Class<?> c = cls("net.minecraft.block.Block");
        return c == null ? Object.class : c;
    }

    public static int maxDamage(Object stack) {
        try {
            Object r = call(stack, "getMaxDamage", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int itemDamage(Object stack) {
        return stackMeta(stack);
    }

    public static boolean damageable(Object stack) {
        try {
            return Boolean.TRUE.equals(call(stack, "isItemStackDamageable", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean enchanted(Object stack) {
        try {
            return Boolean.TRUE.equals(call(stack, "isItemEnchanted", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean canHarvest(Object stack, Object block) {
        try {
            Class<?> bc = findBlockClass();
            return Boolean.TRUE.equals(call(stack, "canHarvestBlock", new Class<?>[]{bc}, block));
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------- enchantments ----------

    private static final Map<String, Integer> ENCH_FALLBACK = new HashMap<String, Integer>();

    static {
        ENCH_FALLBACK.put("efficiency", 32);
        ENCH_FALLBACK.put("fireAspect", 20);
        ENCH_FALLBACK.put("knockback", 19);
        ENCH_FALLBACK.put("protection", 0);
        ENCH_FALLBACK.put("power", 48);
        ENCH_FALLBACK.put("flame", 50);
        ENCH_FALLBACK.put("punch", 49);
    }

    public static int enchId(String name) {
        try {
            Object e = staticField(cls("net.minecraft.enchantment.Enchantment"), name);
            Object id = get(e, "effectId");
            if (id instanceof Number) return ((Number) id).intValue();
        } catch (Throwable ignored) {
        }
        Integer f = ENCH_FALLBACK.get(name);
        return f == null ? -1 : f.intValue();
    }

    public static int enchLevel(int id, Object stack) {
        try {
            if (id < 0 || stack == null) return 0;
            Class<?> hc = cls("net.minecraft.enchantment.EnchantmentHelper");
            Class<?> sc = stack.getClass();
            Object r = callStatic(hc, "getEnchantmentLevel", new Class<?>[]{Integer.TYPE, sc}, Integer.valueOf(id), stack);
            if (r == null) {
                Class<?> isc = cls("net.minecraft.item.ItemStack");
                r = callStatic(hc, "getEnchantmentLevel", new Class<?>[]{Integer.TYPE, isc}, Integer.valueOf(id), stack);
            }
            return r instanceof Number ? ((Number) r).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static float modifierForCreature(Object stack) {
        try {
            Class<?> hc = cls("net.minecraft.enchantment.EnchantmentHelper");
            Class<?> ac = cls("net.minecraft.entity.EnumCreatureAttribute");
            Object undef = ac == null ? null : staticField(ac, "UNDEFINED");
            Class<?> sc = stack.getClass();
            Object r = callStatic(hc, "getModifierForCreature", new Class<?>[]{sc, ac}, stack, undef);
            if (r == null) {
                Class<?> isc = cls("net.minecraft.item.ItemStack");
                r = callStatic(hc, "getModifierForCreature", new Class<?>[]{isc, ac}, stack, undef);
            }
            return r instanceof Number ? ((Number) r).floatValue() : 0.0f;
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    public static double attackDamageAttr(Object stack) {
        try {
            if (stack == null) return 0.0;
            Object mm = call(stack, "getAttributeModifiers", new Class<?>[0]);
            if (mm == null) return 0.0;
            Object entries = call(mm, "entries", new Class<?>[0]);
            if (!(entries instanceof Collection)) return 0.0;
            Class<?> sma = cls("net.minecraft.entity.SharedMonsterAttributes");
            Object atk = sma == null ? null : staticField(sma, "attackDamage");
            String want = atk == null ? null : String.valueOf(call(atk, "getAttributeUnlocalizedName", new Class<?>[0]));
            for (Object en : (Collection<?>) entries) {
                if (!(en instanceof Map.Entry)) continue;
                Object k = ((Map.Entry<?, ?>) en).getKey();
                if (want != null && want.equals(String.valueOf(k))) {
                    Object amt = call(((Map.Entry<?, ?>) en).getValue(), "getAmount", new Class<?>[0]);
                    if (amt instanceof Number) return ((Number) amt).doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    // ---------- controller / minecraft actions ----------

    public static float reach() {
        try {
            Object c = controller();
            Object r = call(c, "getBlockReachDistance", new Class<?>[0]);
            return r instanceof Number ? ((Number) r).floatValue() : 4.5f;
        } catch (Throwable ignored) {
            return 4.5f;
        }
    }

    public static void attackEntity(Object target) {
        try {
            Object c = controller();
            Object p = player();
            if (c == null || p == null || target == null) return;
            Class<?> pc = p.getClass();
            Class<?> ec = target.getClass();
            call(c, "attackEntity", new Class<?>[]{findPlayerClass(), findEntityClass()}, p, target);
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> findPlayerClass() {
        Class<?> c = cls("net.minecraft.entity.player.EntityPlayer");
        return c == null ? Object.class : c;
    }

    private static Class<?> findEntityClass() {
        Class<?> c = cls("net.minecraft.entity.Entity");
        return c == null ? Object.class : c;
    }

    public static void windowClick(int windowId, int slotId, int button, int mode) {
        try {
            Object c = controller();
            Object p = player();
            if (c == null || p == null) return;
            call(c, "windowClick", new Class<?>[]{Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, findPlayerClass()},
                    Integer.valueOf(windowId), Integer.valueOf(slotId),
                    Integer.valueOf(button), Integer.valueOf(mode), p);
        } catch (Throwable ignored) {
        }
    }

    public static void syncItem() {
        try {
            Object c = controller();
            call(c, "syncCurrentPlayItem", new Class<?>[0]);
        } catch (Throwable ignored) {
        }
    }

    public static int rcDelay() {
        try {
            Object v = get(mc(), "rightClickDelayTimer");
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setRcDelay(int v) {
        set(mc(), "rightClickDelayTimer", Integer.valueOf(v));
    }

    // ---------- gui / display ----------

    public static boolean isGuiContainer(Object screen) {
        try {
            Class<?> gc = cls("net.minecraft.client.gui.inventory.GuiContainer");
            return screen != null && gc != null && gc.isInstance(screen);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object hoveredSlot(Object guiContainer) {
        try {
            if (guiContainer == null) return null;
            Field f = fld(guiContainer.getClass(), "theSlot");
            if (f == null) f = fld(guiContainer.getClass(), "field_147006_u");
            return f == null ? null : f.get(guiContainer);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int slotNumber(Object slot) {
        return i(slot, "slotNumber");
    }

    public static int containerId(Object guiContainer) {
        try {
            Object inv = get(guiContainer, "inventorySlots");
            return i(inv, "windowId");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static boolean shiftDown() {
        try {
            Class<?> gs = cls("net.minecraft.client.gui.GuiScreen");
            return Boolean.TRUE.equals(callStatic(gs, "isShiftKeyDown", new Class<?>[0]));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int displayW() {
        return displayProp("getWidth", 854);
    }

    public static int displayH() {
        return displayProp("getHeight", 480);
    }

    private static int displayProp(String method, int def) {
        try {
            Class<?> d = cls("org.lwjgl.opengl.Display");
            Object r = callStatic(d, method, new Class<?>[0]);
            return r instanceof Number ? ((Number) r).intValue() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static float fov() {
        try {
            Object v = get(settings(), "fovSetting");
            return v instanceof Number ? ((Number) v).floatValue() : 90.0f;
        } catch (Throwable ignored) {
            return 90.0f;
        }
    }

    public static int thirdPerson() {
        try {
            Object v = get(settings(), "thirdPersonView");
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---------- scoreboard / network (AntiBot support) ----------

    public static List<String> tabNames() {
        List<String> out = new ArrayList<String>();
        try {
            Object nh = call(mc(), "getNetHandler", new Class<?>[0]);
            Object map = call(nh, "getPlayerInfoMap", new Class<?>[0]);
            if (!(map instanceof Collection)) return out;
            for (Object info : (Collection<?>) map) {
                try {
                    Object profile = call(info, "getGameProfile", new Class<?>[0]);
                    Object name = call(profile, "getName", new Class<?>[0]);
                    if (name != null) out.add(String.valueOf(name));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean isSingleplayer() {
        try {
            return Boolean.TRUE.equals(call(mc(), "isSingleplayer", new Class<?>[0]));
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String serverIP() {
        try {
            Object data = call(mc(), "getCurrentServerData", new Class<?>[0]);
            Object ip = call(data, "getIP", new Class<?>[0]);
            if (ip == null) ip = get(data, "serverIP");
            return ip == null ? "" : String.valueOf(ip);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String sidebarTitle() {
        try {
            Object w = world();
            if (w == null) return null;
            Object sb = call(w, "getScoreboard", new Class<?>[0]);
            if (sb == null) return null;
            Object obj = call(sb, "getObjectiveInDisplaySlot", new Class<?>[]{Integer.TYPE}, Integer.valueOf(1));
            if (obj == null) return null;
            Object name = call(obj, "getDisplayName", new Class<?>[0]);
            return strip(String.valueOf(name));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String strip(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c < 127 && c > 20) sb.append(c);
        }
        return sb.toString();
    }

    // ---------- player predicates ----------

    public static boolean isPlayer(Object o) {
        try {
            Class<?> pc = cls("net.minecraft.entity.player.EntityPlayer");
            return o != null && pc != null && pc.isInstance(o);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean holdingSword() {
        try {
            Object s = getHeldItem(player());
            if (s == null) return false;
            return isItemInstance(stackItem(s), "ItemSword");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Raven holdingWeapon with default Settings (stick=true, rest=false): sword or stick. */
    public static boolean holdingWeapon() {
        try {
            Object s = getHeldItem(player());
            if (s == null) return false;
            Object item = stackItem(s);
            if (isItemInstance(item, "ItemSword")) return true;
            Class<?> items = cls("net.minecraft.init.Items");
            Object stick = items == null ? null : staticField(items, "stick");
            return item != null && stick != null && item.equals(stick);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isTeammate(Object entity) {
        try {
            Object self = player();
            if (self == null || entity == null) return false;
            Object r = call(self, "isOnSameTeam", new Class<?>[]{findEntityLivingBase()}, entity);
            if (Boolean.TRUE.equals(r)) return true;
            String a = displayNameUnf(self);
            String b = displayNameUnf(entity);
            if (a.length() >= 2 && b.length() >= 2 && a.substring(0, 2).equals(b.substring(0, 2))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Class<?> findEntityLivingBase() {
        Class<?> c = cls("net.minecraft.entity.EntityLivingBase");
        return c == null ? Object.class : c;
    }

    public static double toolScore(Object stack, Object block) {
        try {
            if (stack == null || block == null) return 0.0;
            float speed = strVsBlock(stack, block);
            if (speed <= 1.0f) return 0.0;
            int eff = enchLevel(enchId("efficiency"), stack);
            if (eff > 0) speed += eff * eff + 1;
            if (!blockMaterialToolRequired(block) && !canHarvest(stack, block)) {
                speed *= 0.3f;
            }
            double score = speed + durabilityTie(stack);
            return score;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static boolean blockMaterialToolRequired(Object block) {
        try {
            Object mat = call(block, "getMaterial", new Class<?>[0]);
            Object r = call(mat, "isToolNotRequired", new Class<?>[0]);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static double durabilityTie(Object stack) {
        try {
            if (!damageable(stack)) return 0.0;
            int max = maxDamage(stack);
            if (max <= 0) return 0.0;
            return (max - itemDamage(stack)) / (double) max / 1000.0;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    public static double meleeDamage(Object stack) {
        try {
            if (stack == null) return 0.0;
            double dmg = attackDamageAttr(stack);
            dmg += modifierForCreature(stack);
            dmg += enchLevel(enchId("fireAspect"), stack) * 4.0;
            return dmg;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    // ---------- vectors / boxes / traces ----------

    public static Object newVec(double x, double y, double z) {
        try {
            Class<?> vc = cls("net.minecraft.util.Vec3");
            if (vc == null) return null;
            return vc.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE)
                    .newInstance(Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double[] vecXYZ(Object v) {
        try {
            if (v == null) return null;
            return new double[]{d(v, "xCoord"), d(v, "yCoord"), d(v, "zCoord")};
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static double vecDist(Object a, Object b) {
        try {
            Class<?> vc = cls("net.minecraft.util.Vec3");
            Object r = call(a, "distanceTo", new Class<?>[]{vc}, b);
            return r instanceof Number ? ((Number) r).doubleValue() : -1.0;
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    public static Object expandBox(Object box, double x, double y, double z) {
        try {
            return call(box, "expand", new Class<?>[]{Double.TYPE, Double.TYPE, Double.TYPE},
                    Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object addCoordBox(Object box, double x, double y, double z) {
        try {
            return call(box, "addCoord", new Class<?>[]{Double.TYPE, Double.TYPE, Double.TYPE},
                    Double.valueOf(x), Double.valueOf(y), Double.valueOf(z));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object boxIntercept(Object box, Object v1, Object v2) {
        try {
            Class<?> vc = cls("net.minecraft.util.Vec3");
            return call(box, "calculateIntercept", new Class<?>[]{vc, vc}, v1, v2);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean boxContains(Object box, Object vec) {
        try {
            Class<?> vc = cls("net.minecraft.util.Vec3");
            return Boolean.TRUE.equals(call(box, "isVecInside", new Class<?>[]{vc}, vec));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> entitiesExcluding(Object world, Object exclude, Object box) {
        try {
            Class<?> ec = cls("net.minecraft.entity.Entity");
            Class<?> bc = box == null ? null : box.getClass();
            if (ec == null || bc == null) return new ArrayList<Object>();
            Object r = call(world, "getEntitiesWithinAABBExcludingEntity",
                    new Class<?>[]{ec, bc}, exclude, box);
            if (r instanceof List) {
                return new ArrayList<Object>((List<Object>) r);
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<Object>();
    }

    public static String mopType(Object mop) {
        try {
            Object t = get(mop, "typeOfHit");
            return t == null ? "MISS" : String.valueOf(t);
        } catch (Throwable ignored) {
            return "MISS";
        }
    }

    public static Object mopPos(Object mop) {
        try {
            return call(mop, "getBlockPos", new Class<?>[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object mopEntity(Object mop) {
        return get(mop, "entityHit");
    }

    public static Object mopSide(Object mop) {
        return get(mop, "sideHit");
    }

    public static Object mopHitVec(Object mop) {
        return get(mop, "hitVec");
    }

    /** Invokes a public method by name + arity (for signatures with game types). */
    public static Object callAny(Object o, String method, Object... args) {
        try {
            if (o == null) return null;
            for (Method m : o.getClass().getMethods()) {
                if (m.getName().equals(method) && m.getParameterTypes().length == args.length) {
                    return m.invoke(o, args);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Object newBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        try {
            Class<?> bc = cls("net.minecraft.util.AxisAlignedBB");
            if (bc == null) return null;
            return bc.getConstructor(Double.TYPE, Double.TYPE, Double.TYPE,
                    Double.TYPE, Double.TYPE, Double.TYPE).newInstance(
                    Double.valueOf(x1), Double.valueOf(y1), Double.valueOf(z1),
                    Double.valueOf(x2), Double.valueOf(y2), Double.valueOf(z2));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final String[] INTERACTABLE_BLOCKS = {
            "BlockTrapDoor", "BlockDoor", "BlockContainer", "BlockJukebox",
            "BlockFenceGate", "BlockChest", "BlockEnderChest", "BlockEnchantmentTable",
            "BlockBrewingStand", "BlockBed", "BlockDropper", "BlockDispenser",
            "BlockHopper", "BlockAnvil", "BlockNote", "BlockWorkbench"};

    /** Mirrors Raven's BlockUtils.isInteractable(Block). */
    public static boolean isInteractableBlock(Object block) {
        if (block == null) return false;
        for (String s : INTERACTABLE_BLOCKS) {
            try {
                Class<?> c = blockClass(s);
                if (c != null && c.isInstance(block)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static final String[] NON_PLACEABLE = {
            "BlockSnow", "BlockWeb", "BlockSapling", "BlockDaylightDetector",
            "BlockBeacon", "BlockBanner", "BlockEndPortalFrame", "BlockEndPortal",
            "BlockLever", "BlockButton", "BlockSkull", "BlockLiquid", "BlockCactus",
            "BlockDoublePlant", "BlockLilyPad", "BlockCarpet", "BlockTripWire",
            "BlockTripWireHook", "BlockTallGrass", "BlockFlower", "BlockFlowerPot",
            "BlockSign", "BlockLadder", "BlockTorch", "BlockRedstoneTorch",
            "BlockStairs", "BlockSlab", "BlockFence", "BlockPane",
            "BlockStainedGlassPane", "BlockGravel", "BlockClay", "BlockSand",
            "BlockSoulSand", "BlockRailBase"};

    /** Mirrors Raven's Utils.canBePlaced(ItemBlock). */
    public static boolean canBePlacedItem(Object itemBlockObj) {
        try {
            if (itemBlockObj == null) return false;
            Object block = call(itemBlockObj, "getBlock", new Class<?>[0]);
            if (block == null) return false;
            if (isInteractableBlock(block)) return false;
            for (String s : NON_PLACEABLE) {
                try {
                    Class<?> c = blockClass(s);
                    if (c != null && c.isInstance(block)) return false;
                } catch (Throwable ignored) {
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
