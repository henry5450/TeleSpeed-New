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
 * <p>旧版写法通过 assets/xposed_init + IXposedHookLoadPackage/IXposedHookZygoteInit 接入；
 * 现代写法改为继承 {@link XposedModule}，入口类由 META-INF/xposed/java_init.list 声明。
 * 旧的 zygote 注入已不再允许，原先在 initZygote 中完成的初始化移至 onModuleLoaded。
 *
 * <p>Hook 目标为 {@code org.telegram.messenger.FileLoadOperation}。Telegram 的下载参数分两级：
 * <ul>
 *   <li>{@code updateParams()} 计算 {@code downloadChunkSizeBig} / {@code maxDownloadRequests} 等
 *       "档位字段"，但它们在 {@code start()} 里才被折算到真正生效的
 *       {@code currentDownloadChunkSize} / {@code currentMaxDownloadRequests}；</li>
 *   <li>{@code start()} 的折算只在 {@code currentDownloadChunkSize == 0} 时执行一次，
 *       因此直接改档位字段并不足以覆盖所有下载路径（流式播放、故事、纠错降级各走各的分支）。</li>
 * </ul>
 * 所以这里同时挂三处：档位字段、{@code start()} 折算结果、以及请求失败后的纠错回写。
 */
public class SpeedHook extends XposedModule {
    private final static String TAG = "SpeedHook";
    private final static String KEY_SPEED = "speed";
    public final static String BOOST_NONE = "none";
    public final static String BOOST_AVERAGE = "average";
    public final static String BOOST_EXTREME = "extreme";
    private final static int TYPE_ERROR_SUBTITLE = 4;
    private final static long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 2000L;

    private static final String CLASS_FILE_LOAD_OPERATION = "org.telegram.messenger.FileLoadOperation";
    private static final String CLASS_FILE_LOAD_OPERATION_STREAM =
            "org.telegram.messenger.FileLoadOperationStream";
    private static final String CLASS_REQUEST_INFO =
            "org.telegram.messenger.FileLoadOperation$RequestInfo";
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
            hookUpdateParams(classLoader);
            hookStart(classLoader);
            hookProcessRequestResult(classLoader);
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

    /**
     * 覆盖 "档位字段"。这些值随后会被 start() 折算到 current* 字段，
     * 同时 maxCdnParts 直接决定 CDN 分片数量。
     */
    private void hookUpdateParams(ClassLoader classLoader) {
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

                        notifyBoost(param, speed, classLoader);
                    }
                });
    }

    /**
     * 覆盖真正生效的 current* 字段。
     *
     * <p>start() 只在 currentDownloadChunkSize == 0 时把档位字段折算过来，且 isStory / isStream /
     * forceSmallChunk 各走不同分支。这里在其之后按同样的分支规则重新折算并强制写回，
     * 使所有下载路径都遵循用户档位。
     */
    private void hookStart(ClassLoader classLoader) {
        // Telegram 13.x 新增了下载预算机制（普通文档下载时启用），
        // 非会员会被限制在 2 条连接上，这是 13.x 下载明显变慢的根因。
        // 12.10.5 没有该字段，需要先探测再决定是否启用相关修正。
        final boolean hasByteBudgetField = hasField(classLoader, "useDownloadByteBudget");

        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION, classLoader, "start",
                CLASS_FILE_LOAD_OPERATION_STREAM, long.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);

                        if (hasByteBudgetField
                                && XposedHelpers.getBooleanField(operation, "useDownloadByteBudget")) {
                            // 与 Telegram 会员档位对齐（8 条连接）。该值参与
                            // connectionNum = requestsCount & (count - 1) 的计算，
                            // 必须是 2 的幂才能覆盖完整的连接序号。
                            XposedHelpers.setIntField(operation, "downloadConnectionsCount",
                                    DOWNLOAD_CONNECTIONS_COUNT);
                            // 关闭下载字节预算：开启时请求会带上
                            // RequestFlagDownloadByteBudget (0x40000)，
                            // 该标志会让 native 层对下载请求做吞吐限制。
                            XposedHelpers.setBooleanField(operation, "useDownloadByteBudget", false);
                        }

                        if (XposedHelpers.getBooleanField(operation, "forceSmallChunk")) {
                            // 服务器返回 LIMIT_INVALID 后 Telegram 会主动把分块降到 32KB，
                            // 分块大小必须尊重这个纠错结果，但并发数不必跟着砍到 4。
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

    /**
     * 抵消纠错降级对并发数的永久影响。
     *
     * <p>Telegram 收到 LIMIT_INVALID 时会把 currentMaxDownloadRequests 直接写成 4 并置
     * forceSmallChunk，之后只调 startDownloadRequest() 重试、不再经过 start()，
     * 因此并发数不会自行恢复。分块保持降级，并发则按档位复原。
     */
    private void hookProcessRequestResult(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CLASS_FILE_LOAD_OPERATION, classLoader, "processRequestResult",
                CLASS_REQUEST_INFO, CLASS_TL_ERROR, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object operation = param.thisObject;
                        if (!XposedHelpers.getBooleanField(operation, "forceSmallChunk")) {
                            return;
                        }
                        var speed = prefs.getString(KEY_SPEED, BOOST_AVERAGE);
                        XposedHelpers.setIntField(operation, "currentMaxDownloadRequests",
                                maxRequestsOf(speed));
                    }
                });
    }

    /** 探测目标类（含父类）是否存在指定字段，用于兼容不同 Telegram 版本。 */
    private static boolean hasField(ClassLoader classLoader, String fieldName) {
        try {
            Class<?> clazz = XposedHelpers.findClass(CLASS_FILE_LOAD_OPERATION, classLoader);
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
    private void notifyBoost(XC_MethodHook.MethodHookParam param, String speed, ClassLoader classLoader) {
        if (BOOST_NONE.equals(speed)) {
            return;
        }
        try {
            var fileSize = XposedHelpers.getLongField(param.thisObject, "totalBytesCount");
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
                var notificationCenterClass = XposedHelpers.findClass("org.telegram.messenger.NotificationCenter", classLoader);
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
