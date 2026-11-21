# TeleSpeed

Speed up your Telegram download.
提升你的 Telegram 下载速度。

> 本仓库是 [Xposed-Modules-Repo/io.github.tehcneko.telespeed](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.telespeed) 的现代重写版。
> 原模块基于旧的 Xposed API（`de.robv.android.xposed:api:82`），已迁移到 **libxposed API 102**。
>
> This is a modern rewrite of [Xposed-Modules-Repo/io.github.tehcneko.telespeed](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.telespeed),
> migrated from the legacy Xposed API to **libxposed API 102**.

## 与原版的关系

本模块沿用原版 TeleSpeed 的加速思路，并针对新版 Telegram 重构后的下载参数体系做了适配。
`applicationId` 为 `io.github.tehcneko.telespeed.new`，因此它与原版模块**是两个独立的模块**，
可以共存安装，但需要分别启用。

- 原仓库：<https://github.com/Xposed-Modules-Repo/io.github.tehcneko.telespeed>
- 模块作者（原版）：[tehcneko](https://github.com/tehcneko) / Nekogram

## 功能

Hook `org.telegram.messenger.FileLoadOperation`，提高文件下载的分块大小与并发请求数：

| 档位 | 分块大小 | 并发请求数 |
| --- | --- | --- |
| 关闭 | 128 KB | 4（Telegram 默认值） |
| 开启 | 512 KB | 8 |
| 极高 | 1 MB | 12 |

三档均同时作用于大文件、小文件、以及**流式播放**（在线看视频 / 听语音）的下载路径。
下载大文件时会弹出一次提示，五分钟内不重复。

## 要求

- **Android 8.0 (API 26) 及以上**
- **LSPosed 需支持 libxposed API 102**（LSPosed 2.x）
- Telegram 或其分支之一（见下方作用域）

> ⚠️ 从 1.0.6 升级注意：`minSdk` 由 23 提升到 26，`applicationId` 也已变更，
> 需要卸载旧版后重新安装，并在 LSPosed 中重新启用与勾选作用域。

## 作用域

```
org.telegram.messenger
org.telegram.messenger.web
org.telegram.messenger.beta
nekox.messenger
com.cool2645.nekolite
org.telegram.plus
com.iMe.android
org.telegram.BifToGram
ua.itaysonlab.messenger
org.forkclient.messenger.beta
org.aka.messenger
ellipi.messenger
```

## 使用

1. 安装 APK
2. 在 LSPosed 管理器中启用本模块，并勾选你使用的 Telegram 分支
3. 强制停止并重新打开 Telegram
4. 打开模块的设置界面选择档位（默认「开启」）
5. 重新打开 Telegram 使设置生效

## 版本兼容性

| Telegram 版本 | 状态 | 100 MB 实测 |
| --- | --- | --- |
| 12.10.5 (71059) | ✅ 已验证 | 约 8 s |
| 13.0.3 (71839) | ✅ 已验证（v2.0.1+） | 约 10 s |
| 13.2.0 (72809) | ✅ 已验证（v2.1.0+） | 约 11 s |
| 13.3.1 (73469) | ✅ 已验证（v2.2.0+） | 约 5 s |

Telegram 13.x 重构了下载参数体系并新增了下载节流机制，未适配时 100MB 需要约 25 秒。
13.2.0 起**整个下载模块被 R8 混淆**，且类名每次发版都会重新分配（13.2.0 是 `h7`，13.3.1 变成 `d7`，
而 `h7` 被复用给了另一个无关小类）。从 v2.2.0 起改为**候选类名 + 结构特征校验**定位，
不再依赖单一硬编码类名。

## 技术说明

现代 libxposed API 的 Hook 模型（`hook(Executable)` + `Hooker.intercept(Chain)` 链式拦截）
与旧版 `findAndHookMethod` + before/after 差异较大。为了在不改动原有加速逻辑的前提下完成迁移，
本模块在 `compat/` 包中重新实现了一组旧版 API 的等价物：

| 类 | 作用 |
| --- | --- |
| `XposedHelpers` | `findAndHookMethod` / 字段读写 / 方法调用的反射封装 |
| `XC_MethodHook` | 把链式拦截包装回 before / after 回调语义 |
| `XSharedPreferences` | 基于框架 RemotePreferences 的配置读取 |
| `XModuleResources` | 通过 `AssetManager.addAssetPath` 加载模块自身资源 |
| `AndroidAppHelper` | `ActivityThread.currentApplication()` 反射封装 |
| `XposedBridge` | 日志输出与框架接口持有 |

模块自身元数据也不再写在 `AndroidManifest.xml` 中，而是放在
`app/src/main/resources/META-INF/xposed/`：

- `module.prop` — `minApiVersion` / `targetApiVersion` / `staticScope`
- `java_init.list` — 模块入口类
- `scope.list` — 作用域包名

配置读写改用框架的 RemotePreferences（宿主侧 `XposedInterface.getRemotePreferences` 读，
模块应用侧 `XposedService.getRemotePreferences` 读写），不再依赖已被 Android 7.0 禁用的
`MODE_WORLD_READABLE`。

### Telegram 13.x 的下载节流

13.x 的 `FileLoadOperation` 新增了以下字段：

```
downloadConnectionsCount:I            downloadRequestPacer:DownloadRequestPacer
isAudioDocument:Z                     paceDocumentRequests:Z
pacedDownloadRequest:Runnable         useDownloadByteBudget:Z
```

普通文档下载时 `useDownloadByteBudget` 为 true，此时：

- 非会员的 `downloadConnectionsCount` 被设为 2（会员为 8），用于计算
  `connectionNum = requestsCount & (downloadConnectionsCount - 1)`；
- 请求会带上 `RequestFlagDownloadByteBudget (0x40000)`，由 native 层执行吞吐限制。

本模块在 `start()` 的 after 阶段把连接数提到 8，并关闭 `useDownloadByteBudget`，
从而绕过这套限制。字段探测保证在 12.10.5（无这些字段）上不受影响。

### Telegram 13.2.0 的混淆与字节预算

13.2.0 对下载模块做了完整 R8 混淆：类名变为 `h7`，**所有字段名和方法名都被重命名**
（`updateParams()` → `t()`、`start()` → `q()`、`processRequestResult()` → `m()`，
`downloadChunkSizeBig` → `n`、`currentDownloadChunkSize` → `j0` 等）。

更关键的是 `s()` 里的并发计算改成了**按字节预算反推**：

```java
if (useDownloadByteBudget) {
    int budget = isPremium() ? 16 : 2;          // MB
    currentMaxDownloadRequests = budget * 0x100000 / currentDownloadChunkSize;
}
```

也就是说无论分块调多大，总在途数据都被锁死在固定值，调大分块只会相应减少并发数。
本模块在 `start()` 之前预置「当前分块/并发」字段，使这段折算逻辑整体跳过，
并在其后关闭预算开关、恢复连接数。

模块启动时会先探测 `FileLoadOperation` 是否存在；不存在则遍历候选混淆名，并用**结构特征校验**确认：

- 存在无参 void 的 `t()`（updateParams）与 `q()`（start）；
- 存在 `(RequestInfo, TL_error) boolean` 的两参方法 `m()`（processRequestResult）。

仅检查类名是否存在是不够的——R8 会复用旧名字，13.3.1 中的 `h7` 就变成了一个只有 7 个字段的小类。
`RequestInfo` 的类型也不再硬编码，而是从 `m()` 的参数类型反射取得（13.2.0 是 `g7`，13.3.1 是 `c7`）。

候选列表见 `SpeedHook.OBF_OPERATION_CANDIDATES`。若未来 Telegram 再改名，日志会打印每个候选的
字段/方法数与校验结果；即使全部失败，也只会退回「不支持的版本」提示，不会崩溃。

## 构建

```bash
./gradlew assembleRelease
```

需要 JDK 17+ 与 Android SDK（`compileSdk 37` / `build-tools 37.0.0`）。
工具链版本：Gradle 9.8.0、AGP 9.4.1。

## 免责声明
该模块可能导致的账号封禁/设备损坏/数据丢失，用户自行承担风险

## 许可证

[GPL-3.0](LICENSE)
