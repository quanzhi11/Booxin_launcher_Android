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
            resolveGlfw();
            Object posCb = unwrapDelegate(fPos.get(null));
            Object mouseCbRaw = fMouse.get(null);
            Object mouseCb = unwrapDelegate(mouseCbRaw);
            Object enterCb = unwrapDelegate(fEnter.get(null));

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
    }
}
