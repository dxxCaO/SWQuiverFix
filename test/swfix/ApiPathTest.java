package swfix;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * API 可用路径验证：baublesApiAvailable=true 时 transformer 必须零修改。
 */
public class ApiPathTest {
    public static void main(String[] args) throws Exception {
        BaublesStripper.baublesApiAvailable = true;
        try (JarFile jf = new JarFile(new File(args[0]))) {
            JarEntry e = jf.getJarEntry("com/oblivioussp/spartanweaponry/item/ItemQuiverBase.class");
            byte[] orig = readAll(jf.getInputStream(e));
            byte[] out = new BaublesStripper().transform(
                    "com.oblivioussp.spartanweaponry.item/ItemQuiverBase".replace('/', '.'),
                    "com.oblivioussp.spartanweaponry.item.ItemQuiverBase", orig);
            System.out.println("same array = " + (out == orig) + ", len=" + (out == null ? -1 : out.length));
            ClassNode cn = new ClassNode();
            new ClassReader(orig).accept(cn, 0);
            System.out.println("interfaces = " + cn.interfaces);
            for (MethodNode m : cn.methods) {
                if (m.desc.contains("baubles") || m.name.contains("Bauble")) {
                    System.out.println("method = " + m.name + m.desc);
                }
            }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
            }
            return o.toByteArray();
        } finally {
            in.close();
        }
    }
}
