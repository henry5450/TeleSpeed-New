package io.github.tehcneko.telespeed;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.tehcneko.telespeed.compat.AndroidAppHelper;
import io.github.tehcneko.telespeed.compat.XC_MethodHook;
import io.github.tehcneko.telespeed.compat.XModuleResources;
import io.github.tehcneko.telespeed.compat.XSharedPreferences;
import io.github.tehcneko.telespeed.compat.XposedBridge;
import io.github.tehcneko.telespeed.compat.XposedHelpers;

/**
 * 模块入口。已迁移到 modern Xposed API（libxposed API 102）。
 *
 * <p>模块需要同时适配两类 Telegram 版本：
 * <ul>
 *   <li><b>未混淆</b>（12.10.5、13.0.3）：{@code FileLoadOperation} 及其原名字段/方法；</li>
 *   <li><b>已混淆</b>（13.2.0+）：类名 {@code h7}，方法/字段全部被 R8 重命名。</li>
 * </ul>
 * 启动时先探测 {@code FileLoadOperation} 是否存在，不存在则回退到混淆版本。
 *
 * <p>Telegram 的下载参数分两级：{@code updateParams()} 计算
 * {@code downloadChunkSizeBig} / {@code maxDownloadRequests} 等"档位字段"，再由 {@code start()}
 * 折算成真正被请求逻辑消费的 {@code currentDownloadChunkSize} / {@code currentMaxDownloadRequests}。
 * 折算只在 current 字段为 0 时发生一次，因此两条路径都要覆盖。
 */
public class SpeedHook extends XposedModule {
    private final static String TAG = "SpeedHook";
    private final static String KEY_SPEED = "speed";
    public final static String BOOST_NONE = "none";
    public final static String BOOST_AVERAGE = "average";
    public final static String BOOST_EXTREME = "extreme";
    private final static int TYPE_ERROR_SUBTITLE = 4;
    private final static long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 2000L;

    // ---- 未混淆版本（12.10.5 / 13.0.3） ----
    private static final String CLASS_FILE_LOAD_OPERATION = "org.telegram.messenger.FileLoadOperation";
    private static final String CLASS_FILE_LOAD_OPERATION_STREAM =
            "org.telegram.messenger.FileLoadOperationStream";
    private static final String CLASS_REQUEST_INFO =
            "org.telegram.messenger.FileLoadOperation$RequestInfo";

    // ---- 混淆版本（13.2.0+）。类名可能随版本变化，靠特征字符串核对过 ----
    private static final String CLASS_FILE_LOAD_OPERATION_OBF = "org.telegram.messenger.h7";
    private static final String CLASS_REQUEST_INFO_OBF = "org.telegram.messenger.g7";

    private static final String CLASS_TL_ERROR = "org.telegram.tgnet.TLRPC$TL_error";

    /** Telegram 会员档位的下载连接数；非会员默认只有 2。必须是 2 的幂。 */
    private static final int DOWNLOAD_CONNECTIONS_COUNT = 8;

    private static long speedUpShown = 0;
    private XSharedPreferences prefs;
    private XModuleResources res;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // 现代 API 不允许 zygote 注入，模块初始化统一放在这里。
        // 框架接口必须在任何 hook 之前注入兼容层。
        XposedBridge.attach(this, getModuleApplicationInfo().sourceDir);

        prefs = new XSharedPreferences(BuildConfig.APPLICATION_ID, "conf");
        try {
            res = XModuleResources.createInstance(XposedBridge.modulePath(), null);
        } catch (Throwable t) {
            XposedBridge.log("模块资源加载失败，将跳过速度提升提示");
            XposedBridge.log(t);
            res = null;
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        final ClassLoader classLoader = param.getDefaultClassLoader();
        if (BuildConfig.APPLICATION_ID.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        try {
            if (classExists(CLASS_FILE_LOAD_OPERATION, classLoader)) {
                hookLegacy(classLoader);
            } else if (classExists(CLASS_FILE_LOAD_OPERATION_OBF, classLoader)) {
                hookObfuscated(classLoader);
            } else {
                throw new ClassNotFoundException("找不到 FileLoadOperation 或其混淆版本");
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String message = res != null
                            ? res.getString(R.string.unsupported)
                            : "Unsupported Telegram version.";
                    Toast.makeText((Activity) param.thisObject, "TeleSpeed: " + message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    // ==================================================================
    // 未混淆路径（12.10.5 / 13.0.3）
    // ==================================================================

    private void hookLegacy(ClassLoader classLoader) {
        hookLegacyUpdateParams(classLoader);
        hookLegacyStart(classLoader);
        hookLegacyProcessRequestResult(classLoader);
    }

    /** 覆盖档位字段，随后被 start() 折算到 current* 字段。 */
    private void hookLegacyUpdateParams(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION, classLoader, "updateParams",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);
                        int downloadChunkSizeBig = chunkSizeOf(speed);
                        int maxRequests = maxRequestsOf(speed);
                        var maxCdnParts = (int) (DEFAULT_MAX_FILE_SIZE / downloadChunkSizeBig);

                        XposedHelpers.setIntField(param.thisObject, "downloadChunkSizeBig", downloadChunkSizeBig);
                        XposedHelpers.setIntField(param.thisObject, "maxDownloadRequests", maxRequests);
                        XposedHelpers.setIntField(param.thisObject, "maxDownloadRequestsBig", maxRequests);
                        XposedHelpers.setIntField(param.thisObject, "maxCdnParts", maxCdnParts);
                        // 流式播放（在线看视频/听语音）走 isStream 分支，单独取用这两个字段，
                        // 不覆盖的话在线播放完全不受加速影响。
                        XposedHelpers.setIntField(param.thisObject, "downloadChunkSizeAnimation", downloadChunkSizeBig);
                        XposedHelpers.setIntField(param.thisObject, "maxDownloadRequestsAnimation", maxRequests);

                        notifyBoost(param.thisObject, XposedHelpers.getLongField(
                                param.thisObject, "totalBytesCount"), speed, classLoader);
                    }
                });
    }

    /**
     * 覆盖真正生效的 current* 字段，并处理 13.x 新增的下载预算。
     */
    private void hookLegacyStart(ClassLoader classLoader) {
        // Telegram 13.x 新增了 useDownloadByteBudget：普通文档下载时启用，
        // 非会员会被限制在 2 条连接上，同时请求带上 0x40000 标志。
        // 12.10.5 没有该字段，需要先探测。
        final boolean hasByteBudgetField = hasField(CLASS_FILE_LOAD_OPERATION, classLoader, "useDownloadByteBudget");

        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION, classLoader, "start",
                CLASS_FILE_LOAD_OPERATION_STREAM, long.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);

                        if (hasByteBudgetField
                                && XposedHelpers.getBooleanField(operation, "useDownloadByteBudget")) {
                            // 与会员档位对齐（8 条连接）。该值参与
                            // connectionNum = requestsCount & (count - 1)，必须是 2 的幂。
                            XposedHelpers.setIntField(operation, "downloadConnectionsCount",
                                    DOWNLOAD_CONNECTIONS_COUNT);
                            // 关闭字节预算，避免请求带上 RequestFlagDownloadByteBudget(0x40000)。
                            XposedHelpers.setBooleanField(operation, "useDownloadByteBudget", false);
                        }

                        if (XposedHelpers.getBooleanField(operation, "forceSmallChunk")) {
                            // 服务器返回 LIMIT_INVALID 后 Telegram 主动把分块降到 32KB，
                            // 分块必须尊重这个纠错结果，但并发数不必跟着砍。
                            XposedHelpers.setIntField(operation, "currentMaxDownloadRequests",
                                    maxRequestsOf(speed));
                            return;
                        }

                        int chunkSize;
                        int maxRequests;
                        if (XposedHelpers.getBooleanField(operation, "isStory")) {
                            chunkSize = XposedHelpers.getIntField(operation, "downloadChunkSizeBig");
                            maxRequests = XposedHelpers.getIntField(operation, "maxDownloadRequestsBig");
                        } else if (XposedHelpers.getBooleanField(operation, "isStream")) {
                            chunkSize = XposedHelpers.getIntField(operation, "downloadChunkSizeAnimation");
                            maxRequests = XposedHelpers.getIntField(operation, "maxDownloadRequestsAnimation");
                        } else {
                            long totalBytesCount = XposedHelpers.getLongField(operation, "totalBytesCount");
                            int bigFileSizeFrom = XposedHelpers.getIntField(operation, "bigFileSizeFrom");
                            boolean big = totalBytesCount >= bigFileSizeFrom;
                            chunkSize = XposedHelpers.getIntField(operation,
                                    big ? "downloadChunkSizeBig" : "downloadChunkSize");
                            maxRequests = XposedHelpers.getIntField(operation,
                                    big ? "maxDownloadRequestsBig" : "maxDownloadRequests");
                        }

                        XposedHelpers.setIntField(operation, "currentDownloadChunkSize", chunkSize);
                        XposedHelpers.setIntField(operation, "currentMaxDownloadRequests", maxRequests);
                    }
                });
    }

    /** 抵消 LIMIT_INVALID 纠错对并发数的永久影响。 */
    private void hookLegacyProcessRequestResult(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION, classLoader, "processRequestResult",
                CLASS_REQUEST_INFO, CLASS_TL_ERROR, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        if (!XposedHelpers.getBooleanField(operation, "forceSmallChunk")) {
                            return;
                        }
                        XposedHelpers.setIntField(operation, "currentMaxDownloadRequests",
                                maxRequestsOf(prefs.getString(KEY_SPEED, BOOST_AVERAGE)));
                    }
                });
    }

    // ==================================================================
    // 混淆路径（13.2.0+）
    //
    // 字段/方法对照（经 smali 逐条核对）：
    //   t()      = updateParams()
    //   q()      = start()
    //   m(g7,TL_error)Z = processRequestResult()
    //   n:I  = downloadChunkSizeBig        o:I  = maxDownloadRequests
    //   p:I  = maxDownloadRequestsBig      q:I  = maxCdnParts
    //   j0:I = currentDownloadChunkSize    k0:I = currentMaxDownloadRequests
    //   f:Z  = useDownloadByteBudget       g:I  = downloadConnectionsCount
    //   e:Z  = forceSmallChunk             j:Z  = isStory
    //   r0:Z = isStream                    b0:J = totalBytesCount
    // ==================================================================

    private void hookObfuscated(ClassLoader classLoader) {
        hookObfUpdateParams(classLoader);
        hookObfStart(classLoader);
        hookObfProcessRequestResult(classLoader);
    }

    /** t() = updateParams()：计算档位字段。 */
    private void hookObfUpdateParams(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION_OBF, classLoader, "t",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);
                        int chunkSize = chunkSizeOf(speed);
                        int maxRequests = maxRequestsOf(speed);

                        XposedHelpers.setIntField(param.thisObject, "n", chunkSize);
                        XposedHelpers.setIntField(param.thisObject, "o", maxRequests);
                        XposedHelpers.setIntField(param.thisObject, "p", maxRequests);
                        XposedHelpers.setIntField(param.thisObject, "q",
                                (int) (DEFAULT_MAX_FILE_SIZE / chunkSize));

                        notifyBoost(param.thisObject,
                                XposedHelpers.getLongField(param.thisObject, "b0"), speed, classLoader);
                    }
                });
    }

    /**
     * q() = start()：折算 current* 字段。
     *
     * <p>13.2.0 的 q() 只在 currentDownloadChunkSize(j0) == 0 时按分支赋值，
     * 且 isStream 分支硬编码 128KB/4、普通分支里还会调用 s() 用"字节预算"反推并发。
     * 这里在 before 阶段预置 j0/k0，使那个 if 块整体跳过（对齐计算也用我们的分块，
     * 不会产生错位），再在 after 兜底关闭预算开关。
     */
    private void hookObfStart(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION_OBF, classLoader, "q",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        if (XposedHelpers.getBooleanField(operation, "e")) {
                            // forceSmallChunk：让 Telegram 自己走 32KB/4 的纠错分支
                            return;
                        }
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);
                        XposedHelpers.setIntField(operation, "j0", chunkSizeOf(speed));
                        XposedHelpers.setIntField(operation, "k0", maxRequestsOf(speed));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);

                        if (XposedHelpers.getBooleanField(operation, "e")) {
                            // 分块保持降级，只复原并发
                            XposedHelpers.setIntField(operation, "k0", maxRequestsOf(speed));
                            return;
                        }

                        if (XposedHelpers.getBooleanField(operation, "f")) {
                            // 关闭字节预算：请求不再带 RequestFlagDownloadByteBudget(0x40000)
                            XposedHelpers.setBooleanField(operation, "f", false);
                            // 与会员档位对齐
                            XposedHelpers.setIntField(operation, "g", DOWNLOAD_CONNECTIONS_COUNT);
                            // s() 可能已把 k0 改写成"预算/分块"的结果，恢复为档位并发
                            XposedHelpers.setIntField(operation, "k0", maxRequestsOf(speed));
                        }
                    }
                });
    }

    /** m() = processRequestResult()。 */
    private void hookObfProcessRequestResult(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION_OBF, classLoader, "m",
                CLASS_REQUEST_INFO_OBF, CLASS_TL_ERROR, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        if (!XposedHelpers.getBooleanField(operation, "e")) {
                            return;
                        }
                        XposedHelpers.setIntField(operation, "k0",
                                maxRequestsOf(prefs.getString(KEY_SPEED, BOOST_AVERAGE)));
                    }
                });
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static boolean classExists(String className, ClassLoader classLoader) {
        try {
            XposedHelpers.findClass(className, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 探测目标类（含父类）是否存在指定字段，用于兼容不同 Telegram 版本。 */
    private static boolean hasField(String className, ClassLoader classLoader, String fieldName) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
                try {
                    current.getDeclaredField(fieldName);
                    return true;
                } catch (NoSuchFieldException ignored) {
                    // 继续向父类查找
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("探测字段失败: " + fieldName);
            XposedBridge.log(t);
        }
        return false;
    }

    /** 档位对应的分块大小（字节）。BOOST_NONE 即 Telegram 自身的默认值。 */
    private static int chunkSizeOf(String speed) {
        if (BOOST_EXTREME.equals(speed)) {
            return 1024 * 1024;
        }
        if (BOOST_AVERAGE.equals(speed)) {
            return 1024 * 512;
        }
        return 1024 * 128;
    }

    /** 档位对应的并发请求数。BOOST_NONE 即 Telegram 自身的默认值。 */
    private static int maxRequestsOf(String speed) {
        if (BOOST_EXTREME.equals(speed)) {
            return 12;
        }
        if (BOOST_AVERAGE.equals(speed)) {
            return 8;
        }
        return 4;
    }

    /** 大文件下载时提示一次当前档位，五分钟内不重复。 */
    private void notifyBoost(Object operation, long fileSize, String speed, ClassLoader classLoader) {
        if (BOOST_NONE.equals(speed) || res == null) {
            return;
        }
        try {
            if (fileSize <= 15 * 1024 * 1024
                    || System.currentTimeMillis() - speedUpShown <= 1000 * 60 * 5) {
                return;
            }
            speedUpShown = System.currentTimeMillis();

            String speedString;
            if (BOOST_AVERAGE.equals(speed)) {
                speedString = res.getString(R.string.boost_average);
            } else if (BOOST_EXTREME.equals(speed)) {
                speedString = res.getString(R.string.boost_extreme);
            } else {
                speedString = res.getString(R.string.boost_none);
            }
            var title = res.getString(R.string.speed_toast);
            var subtitle = res.getString(R.string.speed_toast_level, "Nekogram", speedString);
            try {
                var notificationCenterClass = XposedHelpers.findClass(
                        "org.telegram.messenger.NotificationCenter", classLoader);
                var globalInstance = XposedHelpers.callStaticMethod(notificationCenterClass, "getGlobalInstance");
                new Handler(Looper.getMainLooper()).post(() -> XposedHelpers.callMethod(
                        globalInstance,
                        "postNotificationName",
                        XposedHelpers.getStaticIntField(notificationCenterClass, "showBulletin"),
                        new Object[]{
                                TYPE_ERROR_SUBTITLE,
                                title,
                                subtitle}));
            } catch (Throwable t) {
                XposedBridge.log(t);
                Toast.makeText(AndroidAppHelper.currentApplication(), title + "\n" + subtitle, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }
}
