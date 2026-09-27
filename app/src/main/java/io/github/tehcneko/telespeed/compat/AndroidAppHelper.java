package io.github.tehcneko.telespeed.compat;

import android.app.Application;

import java.lang.reflect.Method;

/**
 * 旧版 Xposed API 的 {@code AndroidAppHelper} 等价物。
 *
 * <p>现代 API 不再提供直接获取当前 {@link Application} 的入口，
 * 这里通过 {@code ActivityThread.currentApplication()} 反射取得。
 */
public final class AndroidAppHelper {

    private AndroidAppHelper() {
    }

    public static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            return (Application) currentApplication.invoke(null);
        } catch (ReflectiveOperationException e) {
            XposedBridge.log("无法获取 currentApplication");
            XposedBridge.log(e);
            return null;
        }
    }
}
