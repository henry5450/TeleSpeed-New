package io.github.tehcneko.telespeed.compat;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 旧版 Xposed API 的 {@code XSharedPreferences} 等价物。
 *
 * <p>旧实现依赖 {@code MODE_WORLD_READABLE} 直接读取模块的偏好文件，该权限自
 * Android 7.0 起被禁用。现代 libxposed API 通过框架提供的 RemotePreferences
 * 机制跨进程读取模块配置，这里把它包装回旧接口以便既有代码直接复用。
 *
 * <p>若框架未提供 RemotePreferences（例如模块未声明 XposedProvider），
 * 则退化为"全部返回默认值"，模块仍可运行在默认档位上。
 */
public class XSharedPreferences {

    private static final String TAG = "XSharedPreferences";

    private final SharedPreferences delegate;

    /**
     * @param packageName    模块包名。现代 API 下不需要，保留仅为兼容旧调用形式。
     * @param prefFileName   偏好文件名（分组名）
     */
    public XSharedPreferences(String packageName, String prefFileName) {
        SharedPreferences prefs = null;
        try {
            prefs = XposedBridge.requireInterface().getRemotePreferences(prefFileName);
        } catch (Throwable t) {
            XposedBridge.log("无法获取 RemotePreferences(" + prefFileName + ")，将使用默认配置");
            XposedBridge.log(t);
        }
        this.delegate = prefs;
    }

    public String getString(String key, String defValue) {
        return delegate == null ? defValue : delegate.getString(key, defValue);
    }

    public Set<String> getStringSet(String key, Set<String> defValues) {
        return delegate == null ? defValues : delegate.getStringSet(key, defValues);
    }

    public int getInt(String key, int defValue) {
        return delegate == null ? defValue : delegate.getInt(key, defValue);
    }

    public long getLong(String key, long defValue) {
        return delegate == null ? defValue : delegate.getLong(key, defValue);
    }

    public float getFloat(String key, float defValue) {
        return delegate == null ? defValue : delegate.getFloat(key, defValue);
    }

    public boolean getBoolean(String key, boolean defValue) {
        return delegate == null ? defValue : delegate.getBoolean(key, defValue);
    }

    public boolean contains(String key) {
        return delegate != null && delegate.contains(key);
    }

    public Map<String, ?> getAll() {
        return delegate == null ? Collections.emptyMap() : delegate.getAll();
    }

    /** 旧 API 中用于把偏好文件设为全局可读；现代 API 由框架托管，这里是空操作。 */
    public boolean makeWorldReadable() {
        return true;
    }

    /** 旧 API 中用于在宿主进程内重新读取偏好文件；RemotePreferences 会自动同步，空操作即可。 */
    public void reload() {
        if (delegate != null) {
            delegate.getAll();
        }
    }

    public SharedPreferences getDelegate() {
        return delegate;
    }

    public boolean isAvailable() {
        return delegate != null;
    }

    static {
        // 保留 TAG 以便后续排查
        if (false) {
            XposedBridge.log(TAG);
        }
    }
}
