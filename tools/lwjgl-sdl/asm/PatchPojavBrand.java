import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Rewrite Pojav brand / ABI strings inside LWJGL jars to Booxin names. */
public class PatchPojavBrand implements Opcodes {
    private static final Map<String, String> MAP = new LinkedHashMap<>();
    static {
        // Longer keys first via LinkedHashMap insertion order.
        put("libpojavexec.so", "libbooxin_bridge.so");
        put("BooxinPojavLoader", "BooxinBridgeLoader");
        put("POJAV_RENDERER", "BOOXIN_RENDERER");
        put("fixPojavGLContext", "fixBooxinGLContext");
        put("pojavGetRequiredInstanceExtensions", "booxinGetRequiredInstanceExtensions");
        put("pojavGetPhysicalDevicePresentationSupport", "booxinGetPhysicalDevicePresentationSupport");
        put("pojavGetInstanceProcAddress", "booxinGetInstanceProcAddress");
        put("pojavCreateWindowSurface", "booxinCreateWindowSurface");
        put("pojavInitVulkanLoader", "booxinInitVulkanLoader");
        put("pojavVulkanSupported", "booxinVulkanSupported");
        put("pojavGetCurrentContext", "booxinGetCurrentContext");
        put("pojavCreateContext", "booxinCreateContext");
        put("pojavSetInjectorCallback", "booxinSetInjectorCallback");
        put("pojavSetHitResultType", "booxinSetHitResultType");
        put("pojavSetWindowHint", "booxinSetWindowHint");
        put("pojavSwapInterval", "booxinSwapInterval");
        put("pojavStartPumping", "booxinStartPumping");
        put("pojavStopPumping", "booxinStopPumping");
        put("pojavSwapBuffers", "booxinSwapBuffers");
        put("pojavMakeCurrent", "booxinMakeCurrent");
        put("pojavPumpEvents", "booxinPumpEvents");
        put("pojavTerminate", "booxinTerminate");
        put("pojavInitOpenGL", "booxinInitOpenGL");
        put("pojavInit", "booxinInit");
        put("pojavexec", "booxin_bridge");
        put(
            "mPojavRendererInit: Failed to find Pojav renderer name! Renderer-specific initialization may not work properly",
            "BooxinRendererInit: Failed to find Booxin renderer name! Renderer-specific initialization may not work properly"
        );
        put(
            "PojavRendererInit: Failed to find Pojav renderer name! Renderer-specific initialization may not work properly",
            "BooxinRendererInit: Failed to find Booxin renderer name! Renderer-specific initialization may not work properly"
        );
    }

    private static void put(String from, String to) { MAP.put(from, to); }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: PatchPojavBrand <in.jar> <out.jar>");
            System.exit(2);
        }
        Path inJar = Path.of(args[0]);
        Path outJar = Path.of(args[1]);
        final String oldLoader = "org/lwjgl/glfw/BooxinPojavLoader";
        final String newLoader = "org/lwjgl/glfw/BooxinBridgeLoader";
        int[] hits = {0};

        Remapper remapper = new Remapper() {
            @Override public String map(String internalName) {
                if (oldLoader.equals(internalName)) {
                    hits[0]++;
                    return newLoader;
                }
                return internalName;
            }
        };

        try (JarFile jf = new JarFile(inJar.toFile());
             JarOutputStream jos = new JarOutputStream(Files.newOutputStream(outJar))) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                byte[] data;
                try (InputStream is = jf.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                String name = e.getName();
                if (name.equals(oldLoader + ".class")) {
                    name = newLoader + ".class";
                }
                if (name.endsWith(".class")) {
                    data = transformClass(data, remapper, hits);
                } else {
                    String text = new String(data);
                    String out = applyMap(text);
                    if (!out.equals(text)) {
                        hits[0]++;
                        data = out.getBytes();
                    }
                }
                jos.putNextEntry(new JarEntry(name));
                jos.write(data);
                jos.closeEntry();
            }
        }
        System.out.println("PatchPojavBrand " + inJar.getFileName() + " hits~=" + hits[0]);
    }

    static String applyMap(String s) {
        for (Map.Entry<String, String> e : MAP.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    static byte[] transformClass(byte[] in, Remapper remapper, int[] hits) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        ClassVisitor rename = new ClassRemapper(cw, remapper);
        ClassVisitor cv = new ClassVisitor(ASM9, rename) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                String newName = MAP.getOrDefault(name, name);
                if (!newName.equals(name)) hits[0]++;
                MethodVisitor mv = super.visitMethod(access, newName, descriptor, signature, exceptions);
                return new MethodVisitor(ASM9, mv) {
                    @Override public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            String s = (String) value;
                            String out = applyMap(s);
                            if (!out.equals(s)) hits[0]++;
                            super.visitLdcInsn(out);
                            return;
                        }
                        super.visitLdcInsn(value);
                    }
                };
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                Object v = value;
                if (v instanceof String) {
                    String out = applyMap((String) v);
                    if (!out.equals(v)) {
                        hits[0]++;
                        v = out;
                    }
                }
                return super.visitField(access, name, descriptor, signature, v);
            }
        };
        cr.accept(cv, 0);
        return rewriteUtf8Pool(cw.toByteArray(), hits);
    }

    static byte[] rewriteUtf8Pool(byte[] cls, int[] hits) {
        if (cls.length < 10 || cls[0] != (byte) 0xCA || cls[1] != (byte) 0xFE) return cls;
        int cpCount = ((cls[8] & 0xFF) << 8) | (cls[9] & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream(cls.length + 256);
        out.write(cls, 0, 8);
        out.write(0);
        out.write(0);
        int i = 10;
        for (int idx = 1; idx < cpCount; ) {
            int tag = cls[i] & 0xFF;
            if (tag == 1) {
                int len = ((cls[i + 1] & 0xFF) << 8) | (cls[i + 2] & 0xFF);
                String s = new String(cls, i + 3, len);
                String n = applyMap(s);
                if (!n.equals(s)) hits[0]++;
                byte[] nb = n.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(1);
                out.write((nb.length >> 8) & 0xFF);
                out.write(nb.length & 0xFF);
                out.write(nb, 0, nb.length);
                i += 3 + len;
                idx++;
            } else if (tag == 5 || tag == 6) {
                int size = sizeOfTag(tag);
                out.write(cls, i, size);
                i += size;
                idx += 2;
            } else {
                int size = sizeOfTag(tag);
                if (size < 0) return cls;
                out.write(cls, i, size);
                i += size;
                idx++;
            }
        }
        byte[] rest = Arrays.copyOfRange(cls, i, cls.length);
        byte[] head = out.toByteArray();
        head[8] = (byte) ((cpCount >> 8) & 0xFF);
        head[9] = (byte) (cpCount & 0xFF);
        ByteArrayOutputStream all = new ByteArrayOutputStream(head.length + rest.length);
        all.write(head, 0, head.length);
        all.write(rest, 0, rest.length);
        return all.toByteArray();
    }

    static int sizeOfTag(int tag) {
        switch (tag) {
            case 7: case 8: case 16: case 19: case 20: return 3;
            case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: return 5;
            case 5: case 6: return 9;
            case 15: return 4;
            default: return -1;
        }
    }
}
