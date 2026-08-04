package swfix;

import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * 离线验证:对斯巴达 jar 中所有类跑 BaublesStripper,
 * 并用 CheckClassAdapter 校验输出字节码的结构合法性。
 */
public class TestMain {

    public static void main(String[] args) throws Exception {
        String swJarPath = args[0];
        BaublesStripper stripper = new BaublesStripper();
        int total = 0, modified = 0, failed = 0;

        try (JarFile jf = new JarFile(new File(swJarPath))) {
            Enumeration<JarEntry> enumEntries = jf.entries();
            List<String> entryList = new ArrayList<String>();
            while (enumEntries.hasMoreElements()) {
                JarEntry e = enumEntries.nextElement();
                if (e.getName().endsWith(".class") && !e.isDirectory()) {
                    entryList.add(e.getName());
                }
            }
            for (String entryName : entryList) {
                String name = entryName.substring(0, entryName.length() - 6).replace('/', '.');
                if (jf.getJarEntry(entryName) == null) {
                    System.out.println("[MISSING] " + entryName);
                    continue;
                }
                byte[] original = readEntry(jf, entryName);
                byte[] result = stripper.transform(name, name, original);
                total++;
                if (result == null) {
                    continue;
                }
                modified++;
                // 结构校验
                try {
                    ClassReader cr = new ClassReader(result);
                    CheckClassAdapter.verify(cr, false, new java.io.PrintWriter(System.out));
                    System.out.println("[OK] " + name);
                } catch (Throwable t) {
                    failed++;
                    System.out.println("[FAIL] " + name + " -> " + t);
                }
            }
        }
        System.out.println("----");
        System.out.println("total=" + total + " modified=" + modified + " failed=" + failed);
    }

    private static byte[] readEntry(JarFile jf, String entry) throws Exception {
        java.io.InputStream in = jf.getInputStream(jf.getJarEntry(entry));
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
