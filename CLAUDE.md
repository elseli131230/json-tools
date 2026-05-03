# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

IntelliJ IDEA 插件，提供 JSON 格式化和语义 diff 对比功能。全部使用 Kotlin 编写。

## 构建命令

```bash
# 编译构建（生成分发 zip；IDE 会从 Gradle 下载对应 IntelliJ Platform SDK）
./gradlew clean buildPlugin

# 仅编译 Kotlin（不打包插件）
./gradlew compileKotlin

# 启动带插件的沙盒 IDE（调试用）
./gradlew runIde
```

Gradle Wrapper：`./gradlew`（参见 `gradle/wrapper/`）。Gradle **可使用 JDK 11 运行**；当前使用 **Gradle IntelliJ Plugin 1.17.x**，并将 **编译期 SDK** 设为 **IntelliJ IDEA Community `2024.1.7`**（仅解析 OpenAPI，不等同于对用户 IDE 的版本承诺）。若要在构建脚本中使用 **IntelliJ Platform Gradle Plugin 2.x**、并把 Platform 设为 **2024.2（242）及以上**，需要将运行 Gradle 的 JVM 升级到 **JDK 17+**。

**兼容性声明**：`patchPluginXml` 会写入 **`<idea-version since-build="181" until-build="999.*"/>`**（约 2018.1 起、`999.*` 为 JetBrains 生态里常用的「无上限」写法，未来新 IDEA 构建号仍落在声明范围内）。**极旧 IDE** 或 **未来平台大改 API** 时仍可能在运行时失败；Marketplace 审核也可能另有要求。`intellij.updateSinceUntilBuild` 保持 `false`，避免被编译用 SDK 覆盖手填区间。

构建产物：
- `build/libs/json-tools-<version>.jar` — 插件主 JAR（已仪器化/instrumented 产物用于分发）
- `build/distributions/json-tools-<version>.zip` — 标准分发包（`json-tools/lib/*.jar`），可通过 Settings → Plugins → Install Plugin from Disk 安装

## 架构概览

```
JsonToolsWindowFactory   ← IntelliJ ToolWindowFactory 入口
        ↓
JsonToolsPanel           ← 主 UI 面板（CardLayout 切换两套视图）
  ├── SINGLE 模式         ← JBTextArea 单框格式化
  └── COMPARE 模式        ← 左右 JTextPane 双框语义对比
        ↓                        ↓
  JsonFormatter            JsonDiffer
  StrictJsonValidator       （canonical 缓存 + 两阶段数组匹配）
```

### 关键设计决策

- **后台线程**：所有 JSON 处理在单个 daemon 线程池中运行，结果通过 `SwingUtilities.invokeLater` 回到 UI 线程
- **Diff 渲染**：一次性插入全文到 JTextPane，再分段上色，避免频繁 DOM 更新卡顿
- **大数组优化**：>200 元素的数组跳过相似度匹配，只做精确匹配
- **Canonical 缓存**：`IdentityHashMap` 缓存每个 JsonElement 的序列化串，避免重复计算
- **两层 JSON 校验**：`StrictJsonValidator`（手写扫描器拦截尾逗号、注释、单引号等非标准写法）+ `Gson.Strictness.STRICT`（完整 RFC 8259 校验）

### 颜色主题

使用 `JBColor` 支持亮/暗主题自动适配：
- 新增（绿色）、删除（红色）、变更（黄色）

## 主要文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `JsonToolsPanel.kt` | ~783 | UI 面板、事件处理、差异渲染、滚动同步 |
| `JsonDiffer.kt` | ~365 | 语义 diff 引擎、数组匹配策略 |
| `StrictJsonValidator.kt` | ~117 | 非标准 JSON 扫描校验 |
| `JsonFormatter.kt` | ~85 | JSON 解析与格式化 |
| `JsonToolsWindowFactory.kt` | ~16 | ToolWindow 工厂入口 |

## 依赖说明

- **Gson 2.11.0**：作为 `implementation` 打进分发包 `lib/` 目录（与原先 Maven shade 行为一致）
- **Kotlin stdlib**：`compileOnly`，由宿主 IDE 提供
- **IntelliJ Platform**：由 `org.jetbrains.intellij` 从 JetBrains 仓库解析（无需本机 `idea.home`）
- 无单元测试框架（目前无测试代码）
