package io.github.tehcneko.telespeed.compat;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 连接旧版 Xposed API 写法与现代 libxposed API 102 的桥梁。
 *
 * <p>现代 API 的 {@code hook()} 是 {@link io.github.libxposed.api.XposedModule} 的实例方法，
 * 而旧写法里的 {@code XposedHelpers} 是一组静态方法。这里持有框架注入的
 * {@link XposedInterface}，供兼容层的静态方法取用。
 */
public final class XposedBridge {

    public static final String TAG = "TeleSpeed";

    private static volatile XposedInterface sInterface;
    private static volatile String sModulePath;

    private XposedBridge() {
    }

    /** 由模块入口在 {@code onModuleLoaded} 阶段调用，注入框架接口。 */
    public static void attach(XposedInterface xposedInterface, String modulePath) {
        sInterface = xposedInterface;
        sModulePath = modulePath;
    }

    public static XposedInterface requireInterface() {
        XposedInterface xposedInterface = sInterface;
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedInterface 尚未附加：只能在 onModuleLoaded 之后使用");
        }
        return xposedInterface;
    }

    /** 模块自身 APK 的路径，用于加载模块资源。 */
    public static String modulePath() {
        return sModulePath;
    }

    public static void log(String text) {
        XposedInterface xposedInterface = sInterface;
        if (xposedInterface != null) {
            xposedInterface.log(Log.INFO, TAG, text);
        } else {
            Log.i(TAG, text);
        }
    }

    public static void log(Throwable throwable) {
        log(Log.getStackTraceString(throwable));
    }
}
