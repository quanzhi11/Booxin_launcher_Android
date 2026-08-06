package org.lwjgl.glfw;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * HotSpot render-thread delivery into MC's GLFW callbacks.
 * <p>
 * Official jars are obfuscated — never rely on {@code net.minecraft.client.Minecraft}
 * alone. Prefer {@link GLFW} static callback fields (same ClassLoader as bridge-patch)
 * and resolve the window handle from {@code mGLFWWindowMap} / callback captures.
 */
public final class BooxinInputHooks {
    private static volatile Class<?> glfwClass;
    private static volatile Field fPos;
    private static volatile Field fMouse;
    private static volatile Field fEnter;
    private static volatile Field fWindowMap;
    private static volatile Field fWinW;
    private static volatile Field fWinH;
    private static double lastX = Double.NaN;
    private static double lastY = Double.NaN;
    private static boolean cursorEntered;
    private static final int[] lastBtn = {-1, -1, -1};
    private static int diagCount;
    private static int failCount;
    private static long cachedMcWin;

    private BooxinInputHooks() {}

    public static long probeMcWindow() {
        if (cachedMcWin != 0L) return cachedMcWin;
        String[] names = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310",
        };
        for (String name : names) {
            try {
                Class<?> mc = Class.forName(name);
                Object inst = mc.getMethod("getInstance").invoke(null);
                if (inst == null) continue;
                Object win = null;
                for (String m : new String[] {"getWindow", "method_22683"}) {
                    try {
                        win = mc.getMethod(m).invoke(inst);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (win == null) continue;
                for (String m : new String[] {"getWindow", "getHandle", "method_4501", "method_4490"}) {
                    try {
                        Object h = win.getClass().getMethod(m).invoke(win);
                        if (h instanceof Long && (Long) h != 0L) {
                            cachedMcWin = (Long) h;
                            return cachedMcWin;
                        }
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static boolean hasLiveCallbacks() {
        try {
            return fMouse != null && (fMouse.get(null) != null || fPos.get(null) != null);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> resolveGlfw() throws ReflectiveOperationException {
        if (glfwClass != null && hasLiveCallbacks()) return glfwClass;

        ClassLoader[] loaders = {
            BooxinInputHooks.class.getClassLoader(),
            Thread.currentThread().getContextClassLoader(),
            ClassLoader.getSystemClassLoader(),
        };
        ReflectiveOperationException last = null;
        Class<?> fallback = null;
        Field fbMouse = null, fbPos = null, fbEnter = null;
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", true, cl);
                Field mouse = glfw.getField("mGLFWMouseButtonCallback");
                Field pos = glfw.getField("mGLFWCursorPosCallback");
                Field enter = glfw.getField("mGLFWCursorEnterCallback");
                if (mouse.get(null) != null || pos.get(null) != null) {
                    glfwClass = glfw;
                    fMouse = mouse;
                    fPos = pos;
                    fEnter = enter;
                    cacheExtraFields(glfw);
                    return glfw;
                }
                if (fallback == null) {
                    fallback = glfw;
                    fbMouse = mouse;
                    fbPos = pos;
                    fbEnter = enter;
                }
            } catch (ReflectiveOperationException e) {
                last = e;
            }
        }
        if (fallback != null) {
            glfwClass = fallback;
            fMouse = fbMouse;
            fPos = fbPos;
            fEnter = fbEnter;
            cacheExtraFields(fallback);
            return fallback;
        }
        if (last != null) throw last;
        throw new ClassNotFoundException("org.lwjgl.glfw.GLFW");
    }

    private static void cacheExtraFields(Class<?> glfw) {
        try {
            fWindowMap = glfw.getField("mGLFWWindowMap");
        } catch (Throwable ignored) {
            fWindowMap = null;
        }
        try {
            fWinW = glfw.getField("mGLFWWindowWidth");
            fWinH = glfw.getField("mGLFWWindowHeight");
        } catch (Throwable ignored) {
            fWinW = fWinH = null;
        }
    }

    /** Unwrap LWJGL {@code Callback$Container} to the real MC/lambda delegate. */
    private static Object unwrapDelegate(Object cb) {
        if (cb == null) return null;
        try {
            Field d = cb.getClass().getDeclaredField("delegate");
            d.setAccessible(true);
            Object inner = d.get(cb);
            if (inner != null) return inner;
        } catch (Throwable ignored) {
        }
        return cb;
    }

    /**
     * Walk method-ref capture fields (MouseHandler → Minecraft → Window → long handle)
     * and pick a long that appears in {@code mGLFWWindowMap}.
     */
    private static long probeWindowFromCallback(Object mouseCb, Collection<Long> mapKeys) {
        Object del = unwrapDelegate(mouseCb);
        if (del == null) return 0L;
        List<Object> queue = new ArrayList<>();
        queue.add(del);
        for (int depth = 0; depth < 6 && !queue.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object o : queue) {
                if (o == null || o instanceof Number || o instanceof String) continue;
                Class<?> c = o.getClass();
                if (c.isArray() || c.isPrimitive()) continue;
                // Skip JDK internals
                String cn = c.getName();
                if (cn.startsWith("java.") || cn.startsWith("jdk.") || cn.startsWith("sun.")) continue;
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof Long) {
                            long lv = (Long) v;
                            if (lv != 0L && (mapKeys == null || mapKeys.isEmpty() || mapKeys.contains(lv))) {
                                return lv;
                            }
                        } else if (v != null && !v.getClass().isPrimitive()) {
                            next.add(v);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            queue = next;
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> windowMapKeys() {
        List<Long> keys = new ArrayList<>();
        try {
            if (fWindowMap == null) return keys;
            Object map = fWindowMap.get(null);
            if (map == null) return keys;
            Method keySet = map.getClass().getMethod("keySet");
            Object set = keySet.invoke(map);
            for (Object k : (Iterable<Object>) set) {
                if (k instanceof Long) keys.add((Long) k);
                else if (k instanceof Number) keys.add(((Number) k).longValue());
            }
        } catch (Throwable ignored) {
        }
        return keys;
    }

    private static long[] resolveWindows(long fallbackWindow, Object mouseCb) {
        List<Long> keys = windowMapKeys();
        long fromCb = probeWindowFromCallback(mouseCb, keys);
        long fromMc = probeMcWindow();
        List<Long> ordered = new ArrayList<>();
        // Prefer handles MC itself registered in the window map.
        for (Long k : keys) if (k != null && k != 0L && !ordered.contains(k)) ordered.add(k);
        if (fromCb != 0L && !ordered.contains(fromCb)) ordered.add(0, fromCb);
        if (fromMc != 0L && !ordered.contains(fromMc)) ordered.add(0, fromMc);
        if (fallbackWindow != 0L && !ordered.contains(fallbackWindow)) ordered.add(fallbackWindow);
        if (ordered.isEmpty() && fallbackWindow != 0L) ordered.add(fallbackWindow);
        long[] out = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) out[i] = ordered.get(i);
        return out;
    }

    public static void deliverInput(long fallbackWindow, double cx, double cy,
                                    int btn0, int btn1, int btn2) {
        try {
            captureGameClassLoader();
            refreshSnapshot();
            resolveGlfw();
            Object posCb = unwrapDelegate(fPos.get(null));
            Object mouseCbRaw = fMouse.get(null);
            Object mouseCb = unwrapDelegate(mouseCbRaw);
            Object enterCb = unwrapDelegate(fEnter.get(null));
            if (gameClassLoader == null && mouseCb != null) {
                adoptGameClassLoader(mouseCb.getClass().getClassLoader());
            }

            long[] windows = resolveWindows(fallbackWindow, mouseCbRaw);
            if (windows.length == 0) {
                if (failCount < 8) {
                    failCount++;
                    System.err.println("[BooxinInput] deliver skip: no windows");
                }
                return;
            }

            if (diagCount < 10) {
                diagCount++;
                StringBuilder wb = new StringBuilder();
                for (long w : windows) wb.append("0x").append(Long.toHexString(w)).append(',');
                int ww = fWinW != null ? fWinW.getInt(null) : -1;
                int wh = fWinH != null ? fWinH.getInt(null) : -1;
                System.err.println("[BooxinInput] deliver #" + diagCount
                    + " wins=[" + wb + "] fb=0x" + Long.toHexString(fallbackWindow)
                    + " xy=" + (int) cx + "," + (int) cy
                    + " btn=" + btn0 + btn1 + btn2
                    + " glfwWin=" + ww + "x" + wh
                    + " posCb=" + (posCb != null ? posCb.getClass().getName() : "null")
                    + " mouseCb=" + (mouseCb != null ? mouseCb.getClass().getName() : "null")
                    + " thread=" + Thread.currentThread().getName());
            }

            // Deliver to every candidate window — MouseHandler ignores non-matching handles.
            for (long window : windows) {
                if (!cursorEntered && enterCb != null) {
                    invokeEnter(enterCb, window, true);
                }
                if (posCb != null) {
                    invokeCursorPos(posCb, window, cx, cy);
                }
            }
            cursorEntered = true;
            lastX = cx;
            lastY = cy;

            int[] states = {btn0, btn1, btn2};
            for (int btn = 0; btn < 3; btn++) {
                if (lastBtn[btn] == states[btn]) continue;
                if (mouseCb != null) {
                    for (long window : windows) {
                        invokeMouseButton(mouseCb, window, btn, states[btn], 0);
                    }
                    if (diagCount <= 16) {
                        System.err.println("[BooxinInput] mouseBtn btn=" + btn
                            + " act=" + states[btn] + " at " + (int) cx + "," + (int) cy
                            + " nWin=" + windows.length
                            + " primary=0x" + Long.toHexString(windows[0]));
                    }
                } else if (failCount < 8) {
                    failCount++;
                    System.err.println("[BooxinInput] mouseCb null — click dropped");
                }
                lastBtn[btn] = states[btn];
            }
        } catch (Throwable t) {
            if (failCount < 12) {
                failCount++;
                System.err.println("[BooxinInput] deliverInput failed: " + t);
                t.printStackTrace(System.err);
            }
        }
    }

    private static void invokeEnter(Object cb, long window, boolean entered)
        throws ReflectiveOperationException {
        try {
            Method m = cb.getClass().getMethod("invoke", long.class, boolean.class);
            m.invoke(cb, window, entered);
        } catch (NoSuchMethodException e) {
            Method m = cb.getClass().getMethod("invoke", long.class, int.class);
            m.invoke(cb, window, entered ? 1 : 0);
        }
    }

    private static void invokeCursorPos(Object cb, long window, double x, double y)
        throws ReflectiveOperationException {
        Method m = cb.getClass().getMethod("invoke", long.class, double.class, double.class);
        m.invoke(cb, window, x, y);
    }

    private static void invokeMouseButton(Object cb, long window, int button, int action, int mods)
        throws ReflectiveOperationException {
        Method m = cb.getClass().getMethod("invoke", long.class, int.class, int.class, int.class);
        m.invoke(cb, window, button, action, mods);
    }

    public static void reset() {
        glfwClass = null;
        fPos = fMouse = fEnter = fWindowMap = fWinW = fWinH = null;
        lastX = Double.NaN;
        lastY = Double.NaN;
        cursorEntered = false;
        cachedMcWin = 0L;
        for (int i = 0; i < lastBtn.length; i++) lastBtn[i] = -1;
        diagCount = 0;
        failCount = 0;
        injectorReady = false;
        injectorLevel = 0;
        injectorMcClass = null;
        injectorGetInstance = null;
        injectorHitField = null;
        injectorTypeMember = null;
        injectorTypeIsMethod = false;
        cachedPlayerField = null;
        cachedHandMethod = null;
        cachedItemMethod = null;
        cachedEmptyMethod = null;
        mcInstanceClass = null;
        gameClassLoader = null;
        cachedHitType = HIT_UNKNOWN;
        cachedHeldKind = HELD_UNKNOWN;
    }

    public static final int HIT_UNKNOWN = 0;
    public static final int HIT_MISS = 1;
    public static final int HIT_BLOCK = 2;
    public static final int HIT_ENTITY = 3;

    public static final int HELD_UNKNOWN = 0;
    public static final int HELD_EMPTY = 1;
    public static final int HELD_BLOCK = 2;
    public static final int HELD_WEAPON = 3;
    public static final int HELD_OTHER = 4;

    private static volatile ClassLoader gameClassLoader;
    private static volatile int cachedHitType = HIT_UNKNOWN;
    private static volatile int cachedHeldKind = HELD_UNKNOWN;
    private static long lastSnapshotNs;
    private static int snapshotLogCount;
    private static volatile boolean injectorReady;
    private static volatile int injectorLevel;
    private static volatile Class<?> injectorMcClass;
    private static volatile Method injectorGetInstance;
    private static volatile Field injectorHitField;
    private static volatile Object injectorTypeMember;
    private static volatile boolean injectorTypeIsMethod;
    private static volatile Field cachedPlayerField;
    private static volatile Method cachedHandMethod;
    private static volatile Method cachedItemMethod;
    private static volatile Method cachedEmptyMethod;
    private static volatile Class<?> mcInstanceClass;
    private static int injectorFailLog;

    /** Bind the Minecraft/render-thread ClassLoader for later UI-thread queries. */
    public static void setGameClassLoader(ClassLoader classLoader) {
        adoptGameClassLoader(classLoader);
    }

    /**
     * Must run on the game/render thread (e.g. GLFW pump). Reads Minecraft state
     * there and caches results for UI-thread queries.
     */
    public static void refreshSnapshot() {
        try {
            captureGameClassLoader();
            long now = System.nanoTime();
            if (now - lastSnapshotNs < 33_000_000L) return;
            lastSnapshotNs = now;
            int hit = computeHitResultType();
            int held = computeHeldItemKind();
            cachedHitType = hit;
            cachedHeldKind = held;
            if (snapshotLogCount < 12) {
                snapshotLogCount++;
                System.err.println("[BooxinInput] snapshot#" + snapshotLogCount
                    + " hit=" + hit + " held=" + held
                    + " loader=" + (gameClassLoader != null)
                    + " thread=" + Thread.currentThread().getName());
            }
        } catch (Throwable t) {
            if (injectorFailLog < 8) {
                injectorFailLog++;
                System.err.println("[BooxinInput] refreshSnapshot: " + t);
            }
        }
    }

    private static void captureGameClassLoader() {
        adoptGameClassLoader(Thread.currentThread().getContextClassLoader());
    }

    private static void adoptGameClassLoader(ClassLoader cl) {
        if (cl == null || cl == gameClassLoader) return;
        if (gameClassLoader != null && !canSeeMinecraft(cl)) return;
        gameClassLoader = cl;
        synchronized (BooxinInputHooks.class) {
            injectorReady = false;
            injectorMcClass = null;
            injectorGetInstance = null;
            injectorHitField = null;
            injectorTypeMember = null;
            cachedPlayerField = null;
            cachedHandMethod = null;
            cachedItemMethod = null;
            cachedEmptyMethod = null;
            mcInstanceClass = null;
        }
    }

    private static boolean canSeeMinecraft(ClassLoader cl) {
        String[] names = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310",
        };
        for (String n : names) {
            try {
                Class.forName(n, false, cl);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static ClassLoader injectionLoader() {
        ClassLoader g = gameClassLoader;
        if (g != null) return g;
        ClassLoader t = Thread.currentThread().getContextClassLoader();
        if (t != null) return t;
        return BooxinInputHooks.class.getClassLoader();
    }

    /** Parse {@code -Dbooxin.injector=level:class:getInstance:hitField:typeMember}. */
    private static void ensureInjector() {
        if (injectorReady && injectorMcClass != null) return;
        synchronized (BooxinInputHooks.class) {
            if (injectorReady && injectorMcClass != null) return;
            String prop = System.getProperty("booxin.injector");
            if (prop == null || prop.isEmpty()) {
                prop = System.getProperty("fcl.injector");
            }
            boolean ok = false;
            if (prop != null && !prop.isEmpty()) {
                String[] parts = prop.split(":");
                if (parts.length == 5) {
                    try {
                        injectorLevel = Integer.parseInt(parts[0]);
                        ClassLoader cl = injectionLoader();
                        Class<?> mc = Class.forName(parts[1], true, cl);
                        Method getInst = mc.getDeclaredMethod(parts[2]);
                        getInst.setAccessible(true);
                        Field hit = mc.getDeclaredField(parts[3]);
                        hit.setAccessible(true);
                        injectorMcClass = mc;
                        injectorGetInstance = getInst;
                        injectorHitField = hit;
                        injectorTypeMember = parts[4];
                        injectorTypeIsMethod = injectorLevel >= 4;
                        ok = true;
                    } catch (Throwable t) {
                        if (injectorFailLog < 6) {
                            injectorFailLog++;
                            System.err.println("[BooxinInput] injector setup failed: " + t);
                        }
                    }
                }
            }
            if (!ok) {
                ok = trySetupNamedInjector();
            }
            injectorReady = true;
            if (!ok && injectorFailLog < 6) {
                injectorFailLog++;
                System.err.println("[BooxinInput] injector unresolved; gameLoader=" + gameClassLoader);
            }
        }
    }

    private static boolean trySetupNamedInjector() {
        String[][] candidates = {
            {"4", "net.minecraft.client.Minecraft", "getInstance", "hitResult", "getType"},
            {"4", "net.minecraft.client.MinecraftClient", "getInstance", "crosshairTarget", "getType"},
            {"4", "net.minecraft.class_310", "method_1551", "field_1765", "method_17783"},
            {"4", "net.minecraft.client.Minecraft", "m_91087_", "f_91077_", "m_6662_"},
        };
        ClassLoader cl = injectionLoader();
        for (String[] c : candidates) {
            try {
                Class<?> mc = Class.forName(c[1], true, cl);
                Method getInst = mc.getDeclaredMethod(c[2]);
                getInst.setAccessible(true);
                Field hit = mc.getDeclaredField(c[3]);
                hit.setAccessible(true);
                injectorLevel = Integer.parseInt(c[0]);
                injectorMcClass = mc;
                injectorGetInstance = getInst;
                injectorHitField = hit;
                injectorTypeMember = c[4];
                injectorTypeIsMethod = true;
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean resolveTypeMember(Object hitResult) {
        if (injectorTypeMember instanceof Field || injectorTypeMember instanceof Method) {
            return true;
        }
        if (!(injectorTypeMember instanceof String) || hitResult == null) return false;
        String name = (String) injectorTypeMember;
        try {
            if (injectorTypeIsMethod) {
                Method m = hitResult.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                injectorTypeMember = m;
            } else {
                Field f = hitResult.getClass().getDeclaredField(name);
                f.setAccessible(true);
                injectorTypeMember = f;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int queryHitResultType() {
        return cachedHitType;
    }

    private static int computeHitResultType() {
        ensureInjector();
        if (injectorMcClass == null || injectorGetInstance == null || injectorHitField == null) {
            return HIT_UNKNOWN;
        }
        try {
            Object mc = injectorGetInstance.invoke(null);
            if (mc == null) return HIT_UNKNOWN;
            Object hit = injectorHitField.get(mc);
            if (hit == null) {
                return injectorLevel == 2 ? HIT_MISS : HIT_UNKNOWN;
            }
            if (!resolveTypeMember(hit)) return HIT_UNKNOWN;
            Object type;
            if (injectorTypeMember instanceof Method) {
                type = ((Method) injectorTypeMember).invoke(hit);
            } else if (injectorTypeMember instanceof Field) {
                type = ((Field) injectorTypeMember).get(hit);
            } else {
                return HIT_UNKNOWN;
            }
            if (type == null) {
                return injectorLevel == 2 ? HIT_MISS : HIT_UNKNOWN;
            }
            String s = type.toString();
            if ("MISS".equals(s)) return HIT_MISS;
            if ("BLOCK".equals(s) || "TILE".equals(s)) return HIT_BLOCK;
            if ("ENTITY".equals(s)) return HIT_ENTITY;
            return HIT_UNKNOWN;
        } catch (Throwable t) {
            if (injectorFailLog < 8) {
                injectorFailLog++;
                System.err.println("[BooxinInput] computeHitResultType: " + t);
            }
            return HIT_UNKNOWN;
        }
    }

    public static int queryHeldItemKind() {
        return cachedHeldKind;
    }

    private static int computeHeldItemKind() {
        ensureInjector();
        try {
            Object mc = null;
            if (injectorGetInstance != null) {
                mc = injectorGetInstance.invoke(null);
            }
            if (mc == null) {
                mc = probeMinecraftInstance();
            }
            if (mc == null) return HELD_UNKNOWN;
            Object player = getPlayer(mc);
            if (player == null) return HELD_UNKNOWN;
            Object stack = getMainHandStack(player);
            if (stack == null) return HELD_UNKNOWN;
            if (isStackEmpty(stack)) return HELD_EMPTY;
            Object item = getStackItem(stack);
            if (item == null) {
                String sid = findDescriptionId(stack);
                if (sid != null && sid.startsWith("block.")) return HELD_BLOCK;
                if (sid != null && sid.startsWith("item.")) return classifyItemDescription(sid);
                return HELD_OTHER;
            }
            if (isPlaceableBlockItem(item)) return HELD_BLOCK;
            String desc = findDescriptionId(item);
            if (desc == null) desc = findDescriptionId(stack);
            if (desc != null && desc.startsWith("block.")) return HELD_BLOCK;
            if (desc != null && (desc.startsWith("tile.") || desc.contains(".tile."))) return HELD_BLOCK;
            if (desc != null && desc.startsWith("item.")) {
                int byDesc = classifyItemDescription(desc);
                if (byDesc != HELD_OTHER) return byDesc;
            }
            String name = item.getClass().getName();
            String simple = item.getClass().getSimpleName();
            if (isWeaponItemName(name, simple)) return HELD_WEAPON;
            return HELD_OTHER;
        } catch (Throwable t) {
            if (injectorFailLog < 8) {
                injectorFailLog++;
                System.err.println("[BooxinInput] computeHeldItemKind: " + t);
            }
            return HELD_UNKNOWN;
        }
    }

    private static int classifyItemDescription(String desc) {
        String d = desc.toLowerCase();
        if (d.contains("sword") || d.contains("trident") || d.contains("mace")) return HELD_WEAPON;
        return HELD_OTHER;
    }

    private static Object probeMinecraftInstance() {
        String[] names = {
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient",
            "net.minecraft.class_310",
        };
        String[] getters = {"getInstance", "method_1551", "m_91087_", "func_71410_x"};
        for (ClassLoader cl : candidateLoaders()) {
            if (cl == null) continue;
            for (String cn : names) {
                try {
                    Class<?> mc = Class.forName(cn, true, cl);
                    for (String g : getters) {
                        try {
                            Method m = mc.getDeclaredMethod(g);
                            m.setAccessible(true);
                            Object inst = m.invoke(null);
                            if (inst != null) {
                                injectorMcClass = mc;
                                injectorGetInstance = m;
                                adoptGameClassLoader(cl);
                                return inst;
                            }
                        } catch (NoSuchMethodException ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static ClassLoader[] candidateLoaders() {
        return new ClassLoader[] {
            gameClassLoader,
            Thread.currentThread().getContextClassLoader(),
            BooxinInputHooks.class.getClassLoader(),
            ClassLoader.getSystemClassLoader(),
        };
    }

    private static Object getPlayer(Object mc) throws ReflectiveOperationException {
        if (cachedPlayerField != null && mcInstanceClass != null
            && mcInstanceClass.isInstance(mc)) {
            return cachedPlayerField.get(mc);
        }
        Class<?> cls = mc.getClass();
        String[] names = {"player", "field_1724", "f_91074_", "thePlayer"};
        for (String n : names) {
            try {
                Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                Object p = f.get(mc);
                if (p != null) {
                    cachedPlayerField = f;
                    mcInstanceClass = cls;
                    return p;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }
        // Scan declared fields for ClientPlayerEntity-like names.
        for (Field f : cls.getDeclaredFields()) {
            String tn = f.getType().getName();
            if (tn.contains("ClientPlayer") || tn.contains("class_746")
                || tn.endsWith("LocalPlayer")) {
                f.setAccessible(true);
                Object p = f.get(mc);
                if (p != null) {
                    cachedPlayerField = f;
                    mcInstanceClass = cls;
                    return p;
                }
            }
        }
        return null;
    }

    private static Object getMainHandStack(Object player) throws ReflectiveOperationException {
        if (cachedHandMethod != null) {
            try {
                return cachedHandMethod.invoke(player);
            } catch (Throwable ignored) {
                cachedHandMethod = null;
            }
        }
        String[] names = {
            "getMainHandStack", "getMainHandItem", "method_6047", "m_21205_",
            "getHeldItemMainhand", "func_184614_ca"
        };
        Class<?> cls = player.getClass();
        for (String n : names) {
            try {
                Method m = findMethod(cls, n);
                if (m == null || m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                Object stack = m.invoke(player);
                cachedHandMethod = m;
                return stack;
            } catch (Throwable ignored) {
            }
        }
        // Inventory selected slot (ClientPlayer / LocalPlayer).
        for (String invName : new String[] {"getInventory", "method_31548", "m_150109_", "inventory"}) {
            try {
                Object inv;
                if (invName.equals("inventory")) {
                    Field f = null;
                    Class<?> c = cls;
                    while (c != null && f == null) {
                        try { f = c.getDeclaredField("inventory"); } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                    }
                    if (f == null) continue;
                    f.setAccessible(true);
                    inv = f.get(player);
                } else {
                    Method gm = findMethod(cls, invName);
                    if (gm == null || gm.getParameterCount() != 0) continue;
                    gm.setAccessible(true);
                    inv = gm.invoke(player);
                }
                if (inv == null) continue;
                for (String sel : new String[] {
                    "getMainHandStack", "getSelected", "method_7391", "m_36006_", "getCurrentItem"
                }) {
                    Method sm = findMethod(inv.getClass(), sel);
                    if (sm == null || sm.getParameterCount() != 0) continue;
                    sm.setAccessible(true);
                    Object stack = sm.invoke(inv);
                    if (stack != null) return stack;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isStackEmpty(Object stack) {
        try {
            if (cachedEmptyMethod != null) {
                Object r = cachedEmptyMethod.invoke(stack);
                if (r instanceof Boolean) return (Boolean) r;
            }
            String[] names = {"isEmpty", "method_7960", "m_41619_"};
            for (String n : names) {
                Method m = findMethod(stack.getClass(), n);
                if (m == null) continue;
                m.setAccessible(true);
                Object r = m.invoke(stack);
                if (r instanceof Boolean) {
                    cachedEmptyMethod = m;
                    return (Boolean) r;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Object getStackItem(Object stack) {
        try {
            if (cachedItemMethod != null) {
                return cachedItemMethod.invoke(stack);
            }
            String[] names = {"getItem", "method_7909", "m_41720_"};
            for (String n : names) {
                Method m = findMethod(stack.getClass(), n);
                if (m == null) continue;
                m.setAccessible(true);
                Object item = m.invoke(stack);
                cachedItemMethod = m;
                return item;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isPlaceableBlockItem(Object item) {
        Class<?> c = item.getClass();
        while (c != null && c != Object.class) {
            String name = c.getName();
            String simple = c.getSimpleName();
            if (isBlockItemName(name, simple)) return true;
            // Yarn / Mojmap / intermediary / SRG getBlock on BlockItem.
            for (String mName : new String[] {
                "getBlock", "method_7711", "m_40614_", "getBlockState"
            }) {
                try {
                    Method m = findMethod(c, mName);
                    if (m == null || m.getParameterCount() != 0) continue;
                    m.setAccessible(true);
                    Object block = m.invoke(item);
                    if (block != null && looksLikeBlock(block)) return true;
                } catch (Throwable ignored) {
                }
            }
            // Obfuscated BlockItem: any no-arg method returning a Block-like type.
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                String rn = rt.getName();
                if (!(rn.contains("Block") || rn.contains("class_2248") || rn.contains("BlockState"))) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object block = m.invoke(item);
                    if (block != null && looksLikeBlock(block)) return true;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        String desc = findDescriptionId(item);
        return desc != null && (desc.startsWith("block.") || desc.startsWith("tile."));
    }

    private static boolean looksLikeBlock(Object block) {
        String bn = block.getClass().getName();
        return bn.contains("Block") || bn.contains("class_2248") || bn.contains("BlockState");
    }

    /**
     * Best-effort translation / description id, e.g. {@code block.minecraft.oak_planks}.
     * Works across obfuscation when named getters fail.
     */
    private static String findDescriptionId(Object target) {
        if (target == null) return null;
        String[] names = {
            "getDescriptionId", "getTranslationKey", "method_7876", "m_5524_",
            "method_63680", // ItemStack getDescriptionId intermediary variants
            "m_41768_"
        };
        for (String n : names) {
            try {
                Method m = findMethod(target.getClass(), n);
                if (m == null || m.getParameterCount() != 0) continue;
                if (m.getReturnType() != String.class) continue;
                m.setAccessible(true);
                Object r = m.invoke(target);
                if (r instanceof String) {
                    String s = (String) r;
                    if (s.startsWith("block.") || s.startsWith("item.") || s.startsWith("tile.")) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Scan public no-arg String methods for a description-like id.
        try {
            for (Method m : target.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                try {
                    Object r = m.invoke(target);
                    if (!(r instanceof String)) continue;
                    String s = (String) r;
                    if (s.startsWith("block.") || s.startsWith("item.") || s.startsWith("tile.")) {
                        return s;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isBlockItemName(String name, String simple) {
        return name.contains("BlockItem")
            || name.contains("ItemBlock")
            || simple.contains("BlockItem")
            || simple.contains("ItemBlock")
            || name.endsWith(".class_1747")
            || simple.equals("class_1747");
    }

    private static boolean isWeaponItemName(String name, String simple) {
        return name.contains("SwordItem")
            || name.contains("AxeItem")
            || name.contains("TridentItem")
            || name.contains("MaceItem")
            || simple.contains("Sword")
            || simple.contains("Trident")
            || simple.contains("Mace")
            || name.contains("class_1829") // SwordItem intermediary
            || name.contains("class_1743"); // AxeItem intermediary
    }
}
