package swfix;

import java.io.File;
import java.util.Map;
import java.util.jar.JarFile;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/**
 * 修复：斯巴达的武器（第三方重打包版，内嵌 Mixin 0.8.5）与 Baubles 的类加载器冲突。
 *
 * 根因：重打包版 jar 以 FMLCorePlugin + ForceLoadAsMod 方式在启动早期把整个 jar 加入
 * LaunchClassLoader；而 Forge 1.12.2 的 ModClassLoader 会把所有 mod jar 也加进同一个
 * LaunchClassLoader。斯巴达 mod 的构造顺序在 Baubles 之前，因此 ItemQuiverBase 等类
 * 加载时 baubles.api.* 尚未出现在 LaunchClassLoader classpath 中，JVM 验证失败 ->
 * invalidClasses 黑名单 -> 构造阶段 NoClassDefFoundError。
 *
 * 修复（v2.0，零移除）：
 *  1. injectData 阶段把 Baubles jar 加入 LaunchClassLoader，使 baubles.api.* 在
 *     斯巴达类加载前即可解析。斯巴达类的接口/字段/方法/物品全部保持原样，
 *     箭袋的 Baubles 饰品栏功能完整保留（实例上只有一份 baubles.api，无双加载器问题）。
 *  2. BaublesStripper 作为回退：若找不到 Baubles，仍剥离 baubles 引用避免崩溃。
 */
public class SWQuiverFix implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "swfix.BaublesStripper" };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        injectBaublesApi();
    }

    /**
     * 把 Baubles jar 加入 LaunchClassLoader，让斯巴达类能正常解析 baubles.api.*。
     * 成功后 BaublesStripper 不再剥离任何内容（零移除）。
     */
    private static void injectBaublesApi() {
        try {
            File modsDir = findModsDir();
            if (modsDir == null) {
                log("未找到 mods 目录，Baubles 注入跳过，将回退到剥离方案");
                return;
            }
            File[] files = modsDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isFile() || !f.getName().toLowerCase(java.util.Locale.ENGLISH).endsWith(".jar")) {
                        continue;
                    }
                    try (JarFile jf = new JarFile(f)) {
                        if (jf.getJarEntry("baubles/api/IBauble.class") != null) {
                            Launch.classLoader.addURL(f.toURI().toURL());
                            BaublesStripper.baublesApiAvailable = true;
                            log("已把 Baubles jar 加入 LaunchClassLoader: " + f.getName()
                                    + "（保留全部物品与饰品栏功能，无需剥离）");
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            log("mods 目录中未找到含 baubles/api 的 jar，将回退到剥离方案");
        } catch (Throwable t) {
            log("Baubles 注入异常: " + t);
        }
    }

    private static File findModsDir() {
        String userDir = System.getProperty("user.dir");
        File[] candidates = {
                new File("mods"),
                new File(userDir, "mods"),
                new File(new File(userDir, ".."), "mods")
        };
        for (File c : candidates) {
            try {
                if (c.isDirectory()) {
                    return c.getAbsoluteFile();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static void log(String msg) {
        System.out.println("[SWQuiverFix] " + msg);
    }
}
