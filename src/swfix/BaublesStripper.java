package swfix;

import java.util.Iterator;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * 剥离斯巴达武器类上的 Baubles 引用:
 *  - 移除 implements 中的 baubles.api.* 接口(防止双 ClassLoader 加载同一接口
 *    导致 Baubles 的 instanceof 检查失效);
 *  - 删除字段类型为 baubles.api.* 的字段(JVM 验证时会解析字段类型);
 *  - 挖空方法体内引用 baubles.api.* 的方法(方法签名中的类型验证时不解析,
 *    指令引用的类才会被解析,所以只需清理指令)。
 */
public class BaublesStripper implements IClassTransformer {

    private static final String BAUBLES_API = "baubles/api/";
    private static final String SW_PREFIX = "com/oblivioussp/spartanweaponry/";

    /**
     * 由 SWQuiverFix.injectData 在注入 Baubles jar 成功后置 true。
     * 为 true 时斯巴达类可正常解析 baubles.api.*，一律不做任何修改（零移除）。
     */
    public static boolean baublesApiAvailable = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        String internal = name.replace('.', '/');
        if (!internal.startsWith(SW_PREFIX)) {
            return basicClass;
        }
        if (baublesApiAvailable) {
            // Baubles 已注入 LaunchClassLoader：保留原类（接口/方法/物品全部不动）
            return basicClass;
        }
        try {
            ClassNode node = new ClassNode();
            new ClassReader(basicClass).accept(node, 0);

            boolean modified = stripInterfaces(node);
            modified |= stripFields(node);
            modified |= stripMethods(node);

            if (!modified) {
                return basicClass;
            }
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            SWQuiverFix.log("已剥离 Baubles 引用: " + internal);
            return writer.toByteArray();
        } catch (Throwable t) {
            // 转换失败绝不能阻断类加载,返回原字节让类正常通过
            SWQuiverFix.log("转换失败(跳过) " + internal + ": " + t);
            t.printStackTrace(System.out);
            return basicClass;
        }
    }

    private static boolean referencesBaubles(String internalName) {
        return internalName != null && internalName.startsWith(BAUBLES_API);
    }

    private static boolean stripInterfaces(ClassNode node) {
        boolean changed = false;
        Iterator<String> it = node.interfaces.iterator();
        while (it.hasNext()) {
            if (referencesBaubles(it.next())) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean stripFields(ClassNode node) {
        boolean changed = false;
        Iterator<FieldNode> it = node.fields.iterator();
        while (it.hasNext()) {
            FieldNode f = it.next();
            // 用字符串匹配:ASM 5.2 的 Type.getInternalName() 对数组描述符会 NPE,
            // 且字符串检查可同时覆盖对象与数组元素类型
            if (f.desc.contains("L" + BAUBLES_API)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static boolean stripMethods(ClassNode node) {
        boolean changed = false;
        Iterator<MethodNode> it = node.methods.iterator();
        while (it.hasNext()) {
            MethodNode m = it.next();
            if (methodSignatureReferencesBaubles(m)) {
                // 方法签名（返回类型/参数）引用 baubles：反射（如 Class.getMethods）会解析
                // 签名中的类型并触发加载，必须整个删除。已确认斯巴达 jar 内无调用者。
                it.remove();
                changed = true;
            } else if (methodBodyReferencesBaubles(m)) {
                hollowOut(m);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean methodSignatureReferencesBaubles(MethodNode m) {
        if (m.desc != null && m.desc.contains("L" + BAUBLES_API)) {
            return true;
        }
        if (m.signature != null && m.signature.contains("L" + BAUBLES_API)) {
            return true;
        }
        if (containsBaublesAnnotation(m.visibleAnnotations) || containsBaublesAnnotation(m.invisibleAnnotations)) {
            return true;
        }
        if (m.visibleParameterAnnotations != null || m.invisibleParameterAnnotations != null) {
            int count = Math.max(m.visibleParameterAnnotations == null ? 0 : m.visibleParameterAnnotations.length,
                    m.invisibleParameterAnnotations == null ? 0 : m.invisibleParameterAnnotations.length);
            for (int i = 0; i < count; i++) {
                if (m.visibleParameterAnnotations != null && i < m.visibleParameterAnnotations.length
                        && containsBaublesAnnotation(m.visibleParameterAnnotations[i])) {
                    return true;
                }
                if (m.invisibleParameterAnnotations != null && i < m.invisibleParameterAnnotations.length
                        && containsBaublesAnnotation(m.invisibleParameterAnnotations[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsBaublesAnnotation(java.util.List<org.objectweb.asm.tree.AnnotationNode> list) {
        if (list == null) {
            return false;
        }
        for (org.objectweb.asm.tree.AnnotationNode a : list) {
            if (a.desc != null && a.desc.contains(BAUBLES_API)) {
                return true;
            }
            if (a.values != null) {
                for (int i = 0; i < a.values.size(); i += 2) {
                    Object v = a.values.get(i + 1);
                    if (v instanceof org.objectweb.asm.Type) {
                        if (((org.objectweb.asm.Type) v).getDescriptor().contains(BAUBLES_API)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean methodBodyReferencesBaubles(MethodNode m) {
        if (m.instructions == null || m.instructions.size() == 0) {
            return false;
        }
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                if (referencesBaubles(((MethodInsnNode) insn).owner)) {
                    return true;
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                if (referencesBaubles(fi.owner) || fi.desc.contains("L" + BAUBLES_API)) {
                    return true;
                }
            } else if (insn instanceof TypeInsnNode) {
                if (referencesBaubles(((TypeInsnNode) insn).desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void hollowOut(MethodNode m) {
        InsnList list = new InsnList();
        switch (Type.getReturnType(m.desc).getSort()) {
            case Type.VOID:
                list.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.BOOLEAN:
            case Type.BYTE:
            case Type.CHAR:
            case Type.SHORT:
            case Type.INT:
                list.add(new InsnNode(Opcodes.ICONST_0));
                list.add(new InsnNode(Opcodes.IRETURN));
                break;
            case Type.LONG:
                list.add(new InsnNode(Opcodes.LCONST_0));
                list.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                list.add(new InsnNode(Opcodes.FCONST_0));
                list.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                list.add(new InsnNode(Opcodes.DCONST_0));
                list.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                list.add(new InsnNode(Opcodes.ACONST_NULL));
                list.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
        m.instructions = list;
        m.tryCatchBlocks.clear();
        m.localVariables.clear();
    }
}
