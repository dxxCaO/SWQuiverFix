package swfix;

import java.io.File;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * 检查剥离后的斯巴达类中是否还残留 baubles 引用
 * （接口、字段、方法签名、注解等反射时会解析的位置）。
 */
public class CheckBaubles {

    public static void main(String[] args) throws Exception {
        BaublesStripper stripper = new BaublesStripper();
        try (JarFile jf = new JarFile(new File(args[0]))) {
            java.util.Enumeration<JarEntry> en = jf.entries();
            int total = 0, flagged = 0;
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.isDirectory()) {
                    continue;
                }
                total++;
                byte[] orig = readAll(jf.getInputStream(e));
                byte[] out = stripper.transform(e.getName().substring(0, e.getName().length() - 6).replace('/', '.'),
                        e.getName().substring(0, e.getName().length() - 6).replace('/', '.'), orig);
                if (out == null || out == orig) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                new ClassReader(out).accept(cn, 0);
                StringBuilder issues = new StringBuilder();
                for (String itf : cn.interfaces) {
                    if (itf.contains("baubles")) {
                        issues.append("interface=").append(itf).append(" ");
                    }
                }
                for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
                    if (f.desc.contains("baubles")) {
                        issues.append("field=").append(f.name).append(f.desc).append(" ");
                    }
                    if (f.signature != null && f.signature.contains("baubles")) {
                        issues.append("fieldsig=").append(f.name).append(" ");
                    }
                    if (f.visibleAnnotations != null) {
                        for (org.objectweb.asm.tree.AnnotationNode a : f.visibleAnnotations) {
                            if (a.desc.contains("baubles")) {
                                issues.append("fieldann=").append(f.name).append(" ");
                            }
                        }
                    }
                }
                for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
                    if (m.desc.contains("baubles")) {
                        issues.append("method=").append(m.name).append(m.desc).append(" ");
                    }
                    if (m.signature != null && m.signature.contains("baubles")) {
                        issues.append("methodsig=").append(m.name).append(" ");
                    }
                    if (m.visibleAnnotations != null) {
                        for (org.objectweb.asm.tree.AnnotationNode a : m.visibleAnnotations) {
                            if (a.desc.contains("baubles")) {
                                issues.append("methodann=").append(m.name).append(" ");
                            }
                        }
                    }
                    if (m.visibleParameterAnnotations != null) {
                        for (java.util.List<org.objectweb.asm.tree.AnnotationNode> list : m.visibleParameterAnnotations) {
                            if (list != null) {
                                for (org.objectweb.asm.tree.AnnotationNode a : list) {
                                    if (a.desc.contains("baubles")) {
                                        issues.append("paramann=").append(m.name).append(" ");
                                    }
                                }
                            }
                        }
                    }
                }
                if (issues.length() > 0) {
                    flagged++;
                    System.out.println("[BAUBLES-REMAIN] " + cn.name + " -> " + issues);
                }
            }
            System.out.println("---- total=" + total + " flagged=" + flagged);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
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
