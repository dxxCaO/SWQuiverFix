# SWQuiverFix - 斯巴达的武器 × 星辉魔法 冲突修复 mod

针对 1.12.2 Forge 14.23.5.2859 环境的专用修复 coremod。

## 崩溃根因

原始崩溃：`NoClassDefFoundError: com/oblivioussp/spartanweaponry/item/ItemQuiverArrow`
（链路：`ItemQuiverArrow -> 父类 ItemQuiverBase -> ClassNotFoundException`）。

真正原因是**类加载顺序冲突**，与星辉魔法无关：

1. 使用的斯巴达武器 jar 是第三方重打包版：MANIFEST 声明了
   `FMLCorePlugin` + `ForceLoadAsMod: true`（内嵌 Mixin 0.8.5），FML 在启动早期
   就把整个斯巴达 jar 加入 `LaunchClassLoader`。
2. Forge 1.12.2 的 `ModClassLoader` 实际把所有 mod jar 也加进**同一个**
   `LaunchClassLoader`（`addFile` 调 `LaunchClassLoader.addURL`，`loadClass`
   直接委托），但每个 mod 是在**构造时才**加入的。
3. 斯巴达 mod 构造顺序在 Baubles 之前：加载 `ItemQuiverBase`（`implements
   baubles.api.IBauble, IRenderBauble`）时 `baubles.api.*` 还没出现在
   LaunchClassLoader classpath 中 -> JVM 验证失败 -> 类名写入 `invalidClasses`
   黑名单 -> FML 扫描 `RecipeRegistry` 时加载 `ItemQuiverArrow` 命中黑名单 -> 崩溃。

官方版斯巴达没有此问题（不存在早期 ForceLoadAsMod 把 jar 提前放入
LaunchClassLoader 的情况）。

## 修复原理（v2.0：零移除）

`SWQuiverFix.injectData`（coremod 早期阶段，远早于任何 mod 构造）在 mods 目录中
定位 Baubles jar（含 `baubles/api/IBauble.class` 的那个），并调用
`Launch.classLoader.addURL(...)` 把它加入 LaunchClassLoader：

- 斯巴达类加载时 `baubles.api.*` 已经可解析，`ItemQuiverBase` 等类**原样通过**，
  **不剥离任何接口/字段/方法，不删除任何物品**；
- 因为所有 mod 类（含 Baubles mod 自身）都从同一个 LaunchClassLoader 加载，
  `baubles.api.IBauble` 只有一份，`instanceof` 正常，**箭袋的 Baubles 饰品栏
  功能完整保留**；
- `BaublesStripper` 仅作为回退：若找不到 Baubles（mods 目录无含 baubles/api 的
  jar），才剥离 baubles 引用避免崩溃（此时自然也不存在饰品栏功能）。

## 实测结果（2026-08-01）

正常启动日志应出现：

```
[SWQuiverFix] 已把 Baubles jar 加入 LaunchClassLoader: [饰品栏] Baubles-1.12-1.5.2.jar（保留全部物品与饰品栏功能，无需剥离）
```

且**没有** `已剥离 Baubles 引用` 记录（零修改）。随后
`Forge Mod Loader has successfully loaded 7 mods`，游戏正常进入主菜单。

## 安装

1. 把 `SWQuiverFix-1.0.jar` 放入 mods 目录（已部署）。
2. 确保 `星辉魔法`、`饰品栏(Baubles)`、`斯巴达的武器` 三个 jar 均以 `.jar`
   后缀启用（原 `.disabled` 后缀会导致找不到 Baubles）。
3. 启动游戏。

## 回滚

删除 mods 目录中的 `SWQuiverFix-1.0.jar` 即可。

## 重新编译

```bash
# JDK 8+ 均可（class 目标版本 52）
javac -source 8 -target 8 -encoding UTF-8 \
  -cp "lib/launchwrapper-1.12.jar;lib/forge-1.12.2-14.23.5.2859.jar;lib/asm-debug-all-5.2.jar" \
  -d out src/swfix/*.java
jar cfm SWQuiverFix-1.0.jar META-INF/MANIFEST.MF -C out "swfix/SWQuiverFix.class" -C out "swfix/BaublesStripper.class"
```

离线验证（回退剥离路径结构校验、baubles 残留检查）：

```bash
java -cp "out;lib/launchwrapper-1.12.jar;lib/forge-1.12.2-14.23.5.2859.jar;lib/asm-debug-all-5.2.jar" \
  swfix.TestMain "<斯巴达jar路径>"
java -cp "out;lib/asm-debug-all-5.2.jar" \
  swfix.CheckBaubles "<斯巴达jar路径>"
```

## 文件结构

```
SWQuiverFix/
├── SWQuiverFix-1.0.jar       # 已打包的修复 mod
├── src/swfix/                # 源码
│   ├── SWQuiverFix.java      # coremod 入口（注入 Baubles jar + 注册 transformer）
│   └── BaublesStripper.java  # 回退 transformer：仅在无 Baubles 时剥离
├── test/swfix/TestMain.java  # 离线验证工具（结构校验）
├── test/swfix/CheckBaubles.java  # 离线验证工具（baubles 残留检查）
├── lib/                      # 编译依赖（launchwrapper / forge / asm）
└── META-INF/MANIFEST.MF
```
